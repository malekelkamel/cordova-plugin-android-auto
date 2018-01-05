package com.bhvr.android.auto;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.v4.media.MediaBrowserCompat.MediaItem;
import android.support.v4.media.MediaBrowserServiceCompat;
import android.support.v4.media.MediaDescriptionCompat;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentMap;

import static android.media.MediaMetadata.METADATA_KEY_ALBUM_ART_URI;
import static android.media.MediaMetadata.METADATA_KEY_MEDIA_ID;
import static android.media.MediaMetadata.METADATA_KEY_MEDIA_URI;
import static android.media.MediaMetadata.METADATA_KEY_TITLE;
import android.media.MediaPlayer;
import android.support.v4.media.session.PlaybackStateCompat;

/**
 * This class provides a MediaBrowser through a service. It exposes the media library to a browsing
 * client, through the onGetRoot and onLoadChildren methods. It also creates a MediaSession and
 * exposes it through its MediaSession.Token, which allows the client to create a MediaController
 * that connects to and send control commands to the MediaSession remotely. This is useful for
 * user interfaces that need to interact with your media session, like Android Auto. You can
 * (should) also use the same service from your app's UI, which gives a seamless playback
 * experience to the user.
 * <p>
 * To implement a MediaBrowserService, you need to:
 * <p>
 * <ul>
 * <p>
 * <li> Extend {@link MediaBrowserServiceCompat}, implementing the media browsing
 * related methods {@link MediaBrowserServiceCompat#onGetRoot} and
 * {@link MediaBrowserServiceCompat#onLoadChildren};
 * <li> In onCreate, start a new {@link MediaSessionCompat} and notify its parent
 * with the session's token {@link MediaBrowserServiceCompat#setSessionToken};
 * <p>
 * <li> Set a callback on the {@link MediaSessionCompat#setCallback(MediaSessionCompat.Callback)}.
 * The callback will receive all the user's actions, like play, pause, etc;
 * <p>
 * <li> Handle all the actual music playing using any method your app prefers (for example,
 * {@link android.media.MediaPlayer})
 * <p>
 * <li> Update playbackState, "now playing" metadata and queue, using MediaSession proper methods
 * {@link MediaSessionCompat#setPlaybackState(android.support.v4.media.session.PlaybackStateCompat)}
 * {@link MediaSessionCompat#setMetadata(android.support.v4.media.MediaMetadataCompat)} and
 * {@link MediaSessionCompat#setQueue(java.util.List)})
 * <p>
 * <li> Declare and export the service in AndroidManifest with an intent receiver for the action
 * android.media.browse.MediaBrowserService
 * <p>
 * </ul>
 * <p>
 * To make your app compatible with Android Auto, you also need to:
 * <p>
 * <ul>
 * <p>
 * <li> Declare a meta-data tag in AndroidManifest.xml linking to a xml resource
 * with a &lt;automotiveApp&gt; root element. For a media app, this must include
 * an &lt;uses name="media"/&gt; element as a child.
 * For example, in AndroidManifest.xml:
 * &lt;meta-data android:name="com.google.android.gms.car.application"
 * android:resource="@xml/automotive_app_desc"/&gt;
 * And in res/values/automotive_app_desc.xml:
 * &lt;automotiveApp&gt;
 * &lt;uses name="media"/&gt;
 * &lt;/automotiveApp&gt;
 * <p>
 * </ul>
 */
public class CogecoStreamingService extends MediaBrowserServiceCompat {

    private MediaSessionCompat mSession;
    private MusicProvider mMusicProvider;
    private PlaybackManager mPlayback;
    private List<MediaMetadataCompat>  stations;
    private String currentMediaId;
    private int currentMediaIndex;
    private int stationsLength;
    public static final String EXTRA_METADATA_ADVERTISEMENT =
            "android.media.metadata.ADVERTISEMENT";

    @Override
    public void onCreate() {
        super.onCreate();

        mSession = new MediaSessionCompat(this, "CogecoStreamingService");
        setSessionToken(mSession.getSessionToken());
        mSession.setCallback(new MediaSessionCallback());
        mSession.setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS |
                MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS);


        IntentFilter filter = new IntentFilter("com.google.android.gms.car.media.STATUS");
        mMusicProvider = new MusicProvider();

        final MediaPlayer mediaPlayer = new MediaPlayer();


        BroadcastReceiver receiver = new BroadcastReceiver() {

            public void onReceive(Context context, Intent intent) {
                String status = intent.getStringExtra("media_connection_status");
                boolean isConnectedToCar = "media_connected".equals(status);
                // adjust settings based on the connection status
            }
        };
        registerReceiver(receiver, filter);
        mPlayback = new PlaybackManager(
                        this,
                        new PlaybackManager.Callback() {
                            @Override
                            public void onPlaybackStatusChanged(PlaybackStateCompat state) {
                                mSession.setPlaybackState(state);
                             }
                        });

    }

    @Override
    public void onDestroy() {
        mSession.release();
    }

    @Override
    public BrowserRoot onGetRoot(@NonNull String clientPackageName,
                                 int clientUid,
                                 Bundle rootHints) {
        if(mSession.isActive()) {
            mSession.setActive(true);
        }

        return new BrowserRoot("root", null);
    }

    @Override
    public void onLoadChildren(@NonNull final String parentMediaId,
                               @NonNull final Result<List<MediaItem>> result) {

        if (!mMusicProvider.isInitialized()) {
            // Use result.detach to allow calling result.sendResult from another thread:
            result.detach();

            mMusicProvider.retrieveMediaAsync(new MusicProvider.Callback() {
                @Override
                public void onMusicCatalogReady(boolean success) {
                    if (success) {
                        loadChildrenImpl(parentMediaId, result);
                    } else {
                        result.sendResult(new ArrayList<MediaItem>());

                    }
                }
            });

        } else {
            // If our music catalog is already loaded/cached, load them into result immediately
            loadChildrenImpl(parentMediaId, result);
        }
    }

    private void loadChildrenImpl(final String parentMediaId,
                                  final Result<List<MediaItem>> result) {

        ConcurrentMap<String, List<MediaMetadataCompat>> stationsList = mMusicProvider.getStationsList();
        List<MediaItem> mediaItems = new ArrayList<>();
        Iterator<Map.Entry<String, List<MediaMetadataCompat>>> iterator = stationsList.entrySet().iterator();

        while (iterator.hasNext()) {
            Map.Entry<String, List<MediaMetadataCompat>> station = iterator.next();
            List<MediaMetadataCompat>  tmpStations = station.getValue();

            for(int i=0; i< tmpStations.size(); i++) {

                MediaMetadataCompat st = tmpStations.get(i);
                MediaItem item = new MediaItem (
                        new MediaDescriptionCompat.Builder()
                                .setMediaId(st.getString(METADATA_KEY_MEDIA_ID))
                                .setTitle(st.getText(METADATA_KEY_TITLE))
                                .setIconUri(Uri.parse(st.getString(METADATA_KEY_ALBUM_ART_URI)))
                                .setMediaUri(Uri.parse(st.getString(METADATA_KEY_MEDIA_URI)))
                                .build(), MediaItem.FLAG_PLAYABLE
                );
                mediaItems.add(item);
            }
        }
        stationsLength = mediaItems.size();
        result.sendResult(mediaItems);

    }

    private final class MediaSessionCallback extends MediaSessionCompat.Callback {
        @Override
        public void onPlay() {
            MediaMetadataCompat metadata =getMediametaData(currentMediaId);
            mPlayback.setMediaUrl(getMediaUrl(currentMediaId));
            mPlayback.play(metadata);
        }

        @Override
        public void onSkipToQueueItem(long queueId) {
        }

        @Override
        public void onSeekTo(long position) {
        }

        @Override
        public void onPlayFromMediaId(String mediaId, Bundle extras) {
            currentMediaId = mediaId;
            mSession.setActive(true);
            MediaMetadataCompat metadata =getMediametaData(mediaId);
            mSession.setMetadata(metadata);
            mPlayback.setMediaUrl(getMediaUrl(mediaId));
            mPlayback.play(metadata);
        }

        @Override
        public void onPause() {
            mPlayback.pause();
        }

        @Override
        public void onStop() {
            mPlayback.stop();
        }

        @Override
        public void onSkipToNext() {
            if(currentMediaIndex == stationsLength) {
                return;
            }
            mSession.setActive(true);
            MediaMetadataCompat metadata =getMediametaDataByIndex(currentMediaIndex + 1);
            mSession.setMetadata(metadata);
            mPlayback.setMediaUrl(getMediaUrl(currentMediaId));
            mPlayback.play(metadata);
        }

        @Override
        public void onSkipToPrevious() {
            System.out.println("prev");
            if(currentMediaIndex == 0) {
                return;
            }

            mSession.setActive(true);
            MediaMetadataCompat metadata =getMediametaDataByIndex(currentMediaIndex - 1);
            mSession.setMetadata(metadata);
            mPlayback.setMediaUrl(getMediaUrl(currentMediaId));
            mPlayback.play(metadata);


        }

        @Override
        public void onCustomAction(String action, Bundle extras) {
        }

        @Override
        public void onPlayFromSearch(final String query, final Bundle extras) {
            System.out.println("Play");
        }
    }
    private MediaMetadataCompat getMediametaData(String id) {
        MediaMetadataCompat metaData = null;
        ConcurrentMap<String, List<MediaMetadataCompat>> stationsList = mMusicProvider.getStationsList();
        Iterator<Map.Entry<String, List<MediaMetadataCompat>>> iterator = stationsList.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, List<MediaMetadataCompat>> station = iterator.next();
            List<MediaMetadataCompat>  tmpStations = station.getValue();
            for(int i=0; i< tmpStations.size(); i++) {
                MediaMetadataCompat st = tmpStations.get(i);
                if(st.getString(METADATA_KEY_MEDIA_ID).equals(id)) {
                    metaData =  st;
                }
            }
        }
        return metaData;
    }

    private MediaMetadataCompat getMediametaDataByIndex(int index) {
        int j= 0;
        MediaMetadataCompat metaData = null;
        ConcurrentMap<String, List<MediaMetadataCompat>> stationsList = mMusicProvider.getStationsList();
        Iterator<Map.Entry<String, List<MediaMetadataCompat>>> iterator = stationsList.entrySet().iterator();
        while (iterator.hasNext() ) {
            j++;
            Map.Entry<String, List<MediaMetadataCompat>> station = iterator.next();
            List<MediaMetadataCompat>  tmpStations = station.getValue();
            MediaMetadataCompat st = tmpStations.get(index);
            currentMediaId = st.getString(METADATA_KEY_MEDIA_ID);
            if(j == index) {
                metaData =  st;
                currentMediaId = st.getString(METADATA_KEY_MEDIA_ID);
            }

        }
        return metaData;
    }

    private String getMediaUrl(String id) {
        String url = "";
        int j = 0;
        ConcurrentMap<String, List<MediaMetadataCompat>> stationsList = mMusicProvider.getStationsList();
        Iterator<Map.Entry<String, List<MediaMetadataCompat>>> iterator = stationsList.entrySet().iterator();
        while (iterator.hasNext()) {
            j++;
            currentMediaIndex = j;
            Map.Entry<String, List<MediaMetadataCompat>> station = iterator.next();
            List<MediaMetadataCompat>  tmpStations = station.getValue();
            for(int i=0; i< tmpStations.size(); i++) {
                MediaMetadataCompat st = tmpStations.get(i);
                if(st.getString(METADATA_KEY_MEDIA_ID).equals(id)) {
                    url = st.getString(METADATA_KEY_MEDIA_URI);
                }
            }
        }

        return url;
    }
}
