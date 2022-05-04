package ee.forgr.capacitor_updater;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;

import com.android.volley.AuthFailureError;
import com.android.volley.Request;
import com.android.volley.RequestQueue;
import com.android.volley.Response;
import com.android.volley.VolleyError;
import com.android.volley.toolbox.StringRequest;
import com.android.volley.toolbox.Volley;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.ArrayList;
import android.provider.Settings.Secure;

interface Callback {
    void callback(JSONObject jsonObject);
}

public class CapacitorUpdater {
    static final String AB = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    static SecureRandom rnd = new SecureRandom();
    private final String TAG = "Capacitor-updater";
    private final String pluginVersion = "3.2.1";
    private final Context context;
    private final String basePathHot = "versions";
    private final SharedPreferences prefs;
    private final SharedPreferences.Editor editor;

    public String appId = "";
    public String deviceID = "";
    private String versionBuild = "";
    private String versionCode = "";
    private String versionOs = "";
    public String statsUrl = "";

    public CapacitorUpdater (final Context context) throws PackageManager.NameNotFoundException {
        this.context = context;
        this.prefs = this.context.getSharedPreferences("CapWebViewSettings", Activity.MODE_PRIVATE);
        this.editor = this.prefs.edit();
        this.versionOs = Build.VERSION.RELEASE;
        this.deviceID = Secure.getString(context.getContentResolver(), Secure.ANDROID_ID);
        final PackageInfo pInfo = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
        this.versionBuild = pInfo.versionName;
        this.versionCode = Integer.toString(pInfo.versionCode);
    }

    private final FilenameFilter filter = new FilenameFilter() {
        @Override
        public boolean accept(final File f, final String name) {
            // ignore directories generated by mac os x
            return !name.startsWith("__MACOSX") && !name.startsWith(".") && !name.startsWith(".DS_Store");
        }
    };

    private int calcTotalPercent(final int percent, final int min, final int max) {
        return (percent * (max - min)) / 100 + min;
    }

    void notifyDownload(final int percent) {
        return;
    }

    private String randomString(final int len){
        final StringBuilder sb = new StringBuilder(len);
        for(int i = 0; i < len; i++)
            sb.append(AB.charAt(rnd.nextInt(AB.length())));
        return sb.toString();
    }

    private Boolean unzip(final String source, final String dest) {
        final File zipFile = new File(this.context.getFilesDir()  + "/" + source);
        final File targetDirectory = new File(this.context.getFilesDir()  + "/" + dest);
        ZipInputStream zis = null;
        try {
            zis = new ZipInputStream(
                    new BufferedInputStream(new FileInputStream(zipFile)));
        } catch (final FileNotFoundException e) {
            e.printStackTrace();
            return false;
        }
        try {
            ZipEntry ze;
            int count;
            final int buffLength = 8192;
            final byte[] buffer = new byte[buffLength];
            final long totalLength = zipFile.length();
            long readLength = buffLength;
            int percent = 0;
            this.notifyDownload(75);
            while ((ze = zis.getNextEntry()) != null) {
                final File file = new File(targetDirectory, ze.getName());
                final String canonicalPath = file.getCanonicalPath();
                final String canonicalDir = (new File(String.valueOf(targetDirectory))).getCanonicalPath();
                final File dir = ze.isDirectory() ? file : file.getParentFile();
                if (!canonicalPath.startsWith(canonicalDir)) {
                    throw new FileNotFoundException("SecurityException, Failed to ensure directory is the start path : " +
                            canonicalDir + " of " + canonicalPath);
                }
                if (!dir.isDirectory() && !dir.mkdirs())
                    throw new FileNotFoundException("Failed to ensure directory: " +
                            dir.getAbsolutePath());
                if (ze.isDirectory())
                    continue;
                final FileOutputStream fileOut = new FileOutputStream(file);
                try {
                    while ((count = zis.read(buffer)) != -1)
                        fileOut.write(buffer, 0, count);
                } finally {
                    fileOut.close();
                }
                final int newPercent = (int)((readLength * 100) / totalLength);
                if (totalLength > 1 && newPercent != percent) {
                    percent = newPercent;
                    this.notifyDownload(this.calcTotalPercent(percent, 75, 90));
                }
                readLength += ze.getCompressedSize();
            }
        } catch (final Exception e) {
            Log.i(this.TAG, "unzip error", e);
            return false;
        } finally {
            try {
                zis.close();
            } catch (final IOException e) {
                e.printStackTrace();
                return false;
            }
            return true;
        }
    }

    private Boolean flattenAssets(final String source, final String dest) {
        final File current = new File(this.context.getFilesDir()  + "/" + source);
        if (!current.exists()) {
            return false;
        }
        final File fDest = new File(this.context.getFilesDir()  + "/" + dest);
        fDest.getParentFile().mkdirs();
        final String[] pathsName = current.list(this.filter);
        if (pathsName == null || pathsName.length == 0) {
            return false;
        }
        if (pathsName.length == 1 && !pathsName[0].equals("index.html")) {
            final File newFlat =  new File(current.getPath() + "/" + pathsName[0]);
            newFlat.renameTo(fDest);
        } else {
            current.renameTo(fDest);
        }
        current.delete();
        return true;
    }

    private Boolean downloadFile(final String url, final String dest) throws JSONException {
        try {
            final URL u = new URL(url);
            final URLConnection uc = u.openConnection();
            final InputStream is = u.openStream();
            final DataInputStream dis = new DataInputStream(is);
            final long totalLength = uc.getContentLength();
            final int buffLength = 1024;
            final byte[] buffer = new byte[buffLength];
            int length;
            final File downFile = new File(this.context.getFilesDir()  + "/" + dest);
            downFile.getParentFile().mkdirs();
            downFile.createNewFile();
            final FileOutputStream fos = new FileOutputStream(downFile);
            int readLength = buffLength;
            int percent = 0;
            this.notifyDownload(10);
            while ((length = dis.read(buffer))>0) {
                fos.write(buffer, 0, length);
                final int newPercent = (int)((readLength * 100) / totalLength);
                if (totalLength > 1 && newPercent != percent) {
                    percent = newPercent;
                    this.notifyDownload(this.calcTotalPercent(percent, 10, 70));
                }
                readLength += length;
            }
        } catch (final Exception e) {
            Log.e(this.TAG, "downloadFile error", e);
            return false;
        }
        return true;
    }

    private void deleteDirectory(final File file) throws IOException {
        if (file.isDirectory()) {
            final File[] entries = file.listFiles();
            if (entries != null) {
                for (final File entry : entries) {
                    this.deleteDirectory(entry);
                }
            }
        }
        if (!file.delete()) {
            throw new IOException("Failed to delete " + file);
        }
    }

    public String download(final String url) {
        try {
            this.notifyDownload(0);
            final String folderNameZip = this.randomString(10);
            final File fileZip = new File(this.context.getFilesDir()  + "/" + folderNameZip);
            final String folderNameUnZip = this.randomString(10);
            final String version = this.randomString(10);
            final String folderName = this.basePathHot + "/" + version;
            this.notifyDownload(5);
            final Boolean downloaded = this.downloadFile(url, folderNameZip);
            if(!downloaded) return "";
            this.notifyDownload(71);
            final Boolean unzipped = this.unzip(folderNameZip, folderNameUnZip);
            if(!unzipped) return "";
            fileZip.delete();
            this.notifyDownload(91);
            final Boolean flatt = this.flattenAssets(folderNameUnZip, folderName);
            if(!flatt) return "";
            this.notifyDownload(100);
            return version;
        } catch (final Exception e) {
            Log.e(this.TAG, "updateApp error", e);
            return "";
        }
    }

    public ArrayList<String> list() {
        final ArrayList<String> res = new ArrayList<String>();
        final File destHot = new File(this.context.getFilesDir()  + "/" + this.basePathHot);
        Log.i(this.TAG, "list File : " + destHot.getPath());
        if (destHot.exists()) {
            for (final File i : destHot.listFiles()) {
                res.add(i.getName());
            }
        } else {
            Log.i(this.TAG, "No version available" + destHot);
        }
        return res;
    }

    public Boolean delete(final String version, final String versionName) throws IOException {
        final File destHot = new File(this.context.getFilesDir()  + "/" + this.basePathHot + "/" + version);
        if (destHot.exists()) {
            this.deleteDirectory(destHot);
            return true;
        }
        Log.i(this.TAG, "Directory not removed: " + destHot.getPath());
        this.sendStats("delete", versionName);
        return false;
    }

    public Boolean set(final String version, final String versionName) {
        final File destHot = new File(this.context.getFilesDir()  + "/" + this.basePathHot + "/" + version);
        final File destIndex = new File(destHot.getPath()  + "/index.html");
        if (destHot.exists() && destIndex.exists()) {
            this.editor.putString("lastPathHot", destHot.getPath());
            this.editor.putString("serverBasePath", destHot.getPath());
            this.editor.putString("versionName", versionName);
            this.editor.commit();
            this.sendStats("set", versionName);
            return true;
        }
        this.sendStats("set_fail", versionName);
        return false;
    }

    public void getLatest(final String url, final Callback callback) {
        final String deviceID = this.getDeviceID();
        final String appId = this.getAppId();
        final String versionBuild = this.versionBuild;
        final String versionCode = this.versionCode;
        final String versionOs = this.versionOs;
        final String pluginVersion = this.pluginVersion;
        final String versionName = this.getVersionName().equals("") ? "builtin" : this.getVersionName();
        final StringRequest stringRequest = new StringRequest(Request.Method.GET, url,
                new Response.Listener<String>() {
                    @Override
                    public void onResponse(final String response) {
                        try {
                            final JSONObject jsonObject = new JSONObject(response);
                            callback.callback(jsonObject);
                        } catch (final JSONException e) {
                            e.printStackTrace();
                        }
                    }
                }, new Response.ErrorListener() {
            @Override
            public void onErrorResponse(final VolleyError error) {
                Log.e(CapacitorUpdater.this.TAG, "Error getting Latest" +  error);
            }
        }) {
            @Override
            public Map<String, String> getHeaders() throws AuthFailureError {
                final Map<String, String>  params = new HashMap<String, String>();
                params.put("cap_platform", "android");
                params.put("cap_device_id", deviceID);
                params.put("cap_app_id", appId);
                params.put("cap_version_build", versionBuild);
                params.put("cap_version_code", versionCode);
                params.put("cap_version_os", versionOs);
                params.put("cap_version_name", versionName);
                params.put("cap_plugin_version", pluginVersion);
                return params;
            }
        };
        final RequestQueue requestQueue = Volley.newRequestQueue(this.context);
        requestQueue.add(stringRequest);
    }

    public String getLastPathHot() {
        return this.prefs.getString("lastPathHot", "public");
    }

    public String getVersionName() {
        return this.prefs.getString("versionName", "");
    }

    public void reset() {
        final String version = this.prefs.getString("versionName", "");
        this.sendStats("reset", version);
        this.editor.putString("lastPathHot", "public");
        this.editor.putString("serverBasePath", "public");
        this.editor.putString("versionName", "");
        this.editor.commit();
    }

    public void sendStats(final String action, final String version) {
        if (this.getStatsUrl() == "") { return; }
        final URL url;
        final JSONObject json = new JSONObject();
        final String jsonString;
        try {
            url = new URL(this.getStatsUrl());
            json.put("platform", "android");
            json.put("action", action);
            json.put("version_name", version);
            json.put("device_id", this.getDeviceID());
            json.put("version_build", this.versionBuild);
            json.put("version_code", this.versionCode);
            json.put("version_os", this.versionOs);
            json.put("plugin_version", this.pluginVersion);
            json.put("app_id", this.getAppId());
            jsonString = json.toString();
        } catch (final Exception ex) {
            Log.e(this.TAG, "Error get stats", ex);
            return;
        }
        new Thread(new Runnable(){
            @Override
            public void run() {
                HttpURLConnection con = null;
                try {
                    con = (HttpURLConnection) url.openConnection();
                    con.setRequestMethod("POST");
                    con.setRequestProperty("Content-Type", "application/json");
                    con.setRequestProperty("Accept", "application/json");
                    con.setRequestProperty("Content-Length", Integer.toString(jsonString.getBytes().length));
                    con.setDoOutput(true);
                    con.setConnectTimeout(500);
                    final DataOutputStream wr = new DataOutputStream (con.getOutputStream());
                    wr.writeBytes(jsonString);
                    wr.close();
                    final int responseCode = con.getResponseCode();
                    if (responseCode != 200) {
                        Log.e(CapacitorUpdater.this.TAG, "Stats error responseCode: " + responseCode);
                    } else {
                        Log.i(CapacitorUpdater.this.TAG, "Stats send for \"" + action + "\", version " + version);
                    }
                } catch (final Exception ex) {
                    Log.e(CapacitorUpdater.this.TAG, "Error post stats", ex);
                } finally {
                    if (con != null) {
                        con.disconnect();
                    }
                }
            }
        }).start();
    }

    public String getStatsUrl() {
        return this.statsUrl;
    }

    public void setStatsUrl(final String statsUrl) {
        this.statsUrl = statsUrl;
    }

    public String getAppId() {
        return this.appId;
    }

    public void setAppId(final String appId) {
        this.appId = appId;
    }

    public String getDeviceID() {
        return this.deviceID;
    }

    public void setDeviceID(final String deviceID) {
        this.deviceID = deviceID;
    }
}
