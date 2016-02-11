package com.meteor.webapp;

import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okio.BufferedSink;
import okio.Okio;

class AssetBundleDownloader {
    private static final String LOG_TAG = AssetBundleDownloader.class.getSimpleName();

    static final Pattern eTagWithSha1HashPattern = Pattern.compile("\"([0-9a-f]{40})\"");

    public interface Listener {
        public void onDownloadFinished(AssetBundle assetBundle);
    }

    private final AssetBundle assetBundle;
    private final HttpUrl baseUrl;

    private final OkHttpClient httpClient;

    private final Set<AssetBundle.Asset> assetsDownloading;

    private Listener listener;

    public AssetBundleDownloader(AssetBundle assetBundle, HttpUrl baseUrl) {
        this.assetBundle = assetBundle;
        this.baseUrl = baseUrl;

        httpClient = new OkHttpClient.Builder().cache(null).build();
        httpClient.dispatcher().setMaxRequestsPerHost(6);

        assetsDownloading = Collections.newSetFromMap(new ConcurrentHashMap<AssetBundle.Asset,Boolean>());
    }

    public AssetBundle getAssetBundle() {
        return assetBundle;
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void resume() {
        Log.v(LOG_TAG, "Start downloading assets from bundle with version: " + assetBundle.getVersion());

        for (final AssetBundle.Asset asset : assetBundle.getOwnAssets()) {
            assetsDownloading.add(asset);

            HttpUrl url = downloadUrlForAsset(asset);
            Request request = new Request.Builder().url(url).build();
            httpClient.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    Log.w(LOG_TAG, "Error downloading asset: " + asset, e);
                    cancel();
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    if (!verifyResponse(response, asset)) {
                        cancel();
                        return;
                    }

                    // Log.v(LOG_TAG, "Finished downloading asset: " + asset);

                    IOUtils.writeToFile(response.body().source(), asset.getFile());

                    // We don't have a hash for the index page, so we have to parse the runtime config
                    // and compare autoupdateVersionCordova to the version in the manifest to verify
                    // if we downloaded the expected version
                    if (asset.filePath.equals("index.html")) {
                        JSONObject runtimeConfig = assetBundle.getRuntimeConfig();
                        if (runtimeConfig != null) {
                            String expectedVersion = assetBundle.getVersion();
                            String actualVersion = runtimeConfig.optString("autoupdateVersionCordova", null);
                            if (actualVersion != null) {
                                if (!actualVersion.equals(expectedVersion)) {
                                    Log.w(LOG_TAG, "Version mismatch for index page, expected: " + expectedVersion + ", actual: " + actualVersion);
                                    cancel();
                                    return;
                                }
                            }
                        }
                    }

                    assetsDownloading.remove(asset);

                    if (assetsDownloading.isEmpty()) {
                        Log.v(LOG_TAG, "Finished downloading new asset bundle version: " + assetBundle.getVersion());

                        if (listener != null) {
                            listener.onDownloadFinished(assetBundle);
                        }
                    }
                }
            });
        }
    }

    protected HttpUrl downloadUrlForAsset(AssetBundle.Asset asset) {
        String urlPath = asset.urlPath;

        // Remove leading / from URL path because the path should be
        // interpreted relative to the base URL
        if (urlPath.startsWith("/")) {
            urlPath = urlPath.substring(1);
        }

        HttpUrl.Builder builder = baseUrl.newBuilder(urlPath);

        // To avoid inadvertently downloading the default index page when an asset
        // is not found, we add meteor_dont_serve_index=true to the URL unless we
        // are actually downloading the index page.
        if (!asset.filePath.equals("index.html")) {
            builder.addQueryParameter("meteor_dont_serve_index", "true");
        }

        return  builder.build();
    }

    protected boolean verifyResponse(Response response, AssetBundle.Asset asset) {
        if (!response.isSuccessful()) {
            Log.w(LOG_TAG, "Non-success status code " + response.code() + " for asset: " + asset);
            return false;
        }

        // If we have a hash for the asset, and the ETag header also specifies
        // a hash, we compare these to verify if we received the expected asset version
        String expectedHash = asset.hash;
        if (expectedHash != null) {
            String eTag = response.header("etag");

            if (eTag != null) {
                Matcher matcher = eTagWithSha1HashPattern.matcher(eTag);
                if (matcher.find()) {
                    String actualHash = matcher.group(1);

                    if (!actualHash.equals(expectedHash)) {
                        Log.w(LOG_TAG, "Hash mismatch for asset: " + asset);
                        return false;
                    }
                }
            }
        }

        return true;
    }

    public void cancel() {
        httpClient.dispatcher().cancelAll();
    }
}
