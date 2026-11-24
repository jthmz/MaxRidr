package com.maxapacity.ride;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;

import org.json.JSONArray;
import org.json.JSONObject;
import org.robolectric.Shadows;
import org.robolectric.shadows.ShadowNetworkCapabilities;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A stand-in for the Deck on this machine, answering the way deck/server.mjs
 * does for everything deck/APP-LINK.md promises a phone app: state, pairing
 * with a 6-digit code, asking which files are wanted, and taking them. The
 * real link code was also run against the real Deck; this keeps that honest
 * on every build. A few lines of HTTP over a plain socket, so it needs
 * nothing the Android test classpath lacks.
 */
final class FakeDeck {

  private final ServerSocket socket;
  private volatile boolean running = true;
  /** What has arrived, by name. */
  final Map<String, byte[]> stored = Collections.synchronizedMap(new LinkedHashMap<>());
  /** The folder each arrival was sent for, and the device named. */
  final List<String> arrivals = Collections.synchronizedList(new ArrayList<>());
  /** True for a Deck on the network, which wants the phone paired; false for the cable. */
  final boolean wantsKey;
  /** The code showing under Add a screen, or null when none is. */
  volatile String code;
  volatile String key;
  volatile boolean uploads = true;
  volatile int puts;

  FakeDeck(boolean wantsKey) throws IOException {
    this.wantsKey = wantsKey;
    socket = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
    Thread accept = new Thread(() -> {
      while (running) {
        try {
          final Socket s = socket.accept();
          Thread t = new Thread(() -> serve(s));
          t.setDaemon(true);
          t.start();
        } catch (IOException e) {
          return;
        }
      }
    });
    accept.setDaemon(true);
    accept.start();
  }

  String address() {
    return "127.0.0.1:" + socket.getLocalPort();
  }

  void stop() {
    running = false;
    try {
      socket.close();
    } catch (IOException e) {
      // Already closed.
    }
  }

  /** One request, one answer, then the connection is closed. */
  private void serve(Socket s) {
    try (Socket sock = s) {
      InputStream in = new BufferedInputStream(sock.getInputStream());
      ByteArrayOutputStream head = new ByteArrayOutputStream();
      int b;
      while ((b = in.read()) >= 0) {
        head.write(b);
        byte[] h = head.toByteArray();
        int n = h.length;
        if (n >= 4 && h[n - 4] == 13 && h[n - 3] == 10 && h[n - 2] == 13 && h[n - 1] == 10) break;
      }
      String[] lines = new String(head.toByteArray(), StandardCharsets.ISO_8859_1).split("\r\n");
      String[] first = lines[0].split(" ");
      if (first.length < 2) return;
      Map<String, String> headers = new LinkedHashMap<>();
      for (int i = 1; i < lines.length; i++) {
        int colon = lines[i].indexOf(':');
        if (colon > 0) {
          headers.put(lines[i].substring(0, colon).trim().toLowerCase(Locale.US), lines[i].substring(colon + 1).trim());
        }
      }
      String announced = headers.get("content-length");
      byte[] body = announced == null ? new byte[0] : in.readNBytes(Integer.parseInt(announced));
      int q = first[1].indexOf('?');
      String path = q < 0 ? first[1] : first[1].substring(0, q);
      Object[] answer;
      try {
        answer = answer(first[0], path, query(q < 0 ? null : first[1].substring(q + 1)), headers, body);
      } catch (Exception e) {
        answer = new Object[] {500, "{\"ok\":false,\"why\":\"" + e + "\"}"};
      }
      byte[] out = ((String) answer[1]).getBytes(StandardCharsets.UTF_8);
      OutputStream o = sock.getOutputStream();
      o.write(("HTTP/1.1 " + answer[0] + " X\r\nContent-Type: application/json\r\nContent-Length: "
          + out.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
      o.write(out);
      o.flush();
    } catch (IOException e) {
      // The phone hung up.
    }
  }

  private Object[] answer(String method, String path, Map<String, String> q, Map<String, String> headers,
      byte[] body) throws Exception {
    if (path.equals("/api/pair")) {
      JSONObject b = new JSONObject(new String(body, StandardCharsets.UTF_8));
      if (code == null) return new Object[] {200, "{\"ok\":false,\"why\":\"closed\"}"};
      if (!code.equals(b.optString("code"))) return new Object[] {200, "{\"ok\":false,\"why\":\"wrong\"}"};
      code = null;
      key = "k".repeat(43);
      return new Object[] {200, new JSONObject().put("ok", true).put("name", b.optString("name")).put("key", key).toString()};
    }
    // The cable is trusted; the network wants the key this phone was handed.
    if (wantsKey && (key == null || !key.equals(headers.get("x-deck-token")))) {
      return new Object[] {401, "{\"error\":\"not paired\"}"};
    }
    if (path.equals("/api/state")) return new Object[] {200, state()};
    if (path.equals("/api/action")) return new Object[] {200, "{\"ok\":true}"};
    if (path.equals("/api/upload/check")) {
      JSONObject b = new JSONObject(new String(body, StandardCharsets.UTF_8));
      JSONArray need = new JSONArray(), have = new JSONArray(), refused = new JSONArray();
      JSONArray files = b.getJSONArray("files");
      for (int i = 0; i < files.length(); i++) {
        JSONObject f = files.getJSONObject(i);
        String name = f.getString("name");
        String no = refusal(name);
        byte[] here = stored.get(name);
        if (no != null) refused.put(new JSONObject().put("name", name).put("why", no));
        else if (here != null && here.length == f.getLong("size")) have.put(name);
        else need.put(name);
      }
      return new Object[] {200, new JSONObject().put("ok", true).put("need", need).put("have", have).put("refused", refused).toString()};
    }
    if (path.equals("/api/upload") && method.equals("PUT")) {
      String name = q.get("name");
      String no = refusal(name);
      String announced = headers.get("content-length");
      if (no != null) return new Object[] {uploads ? 415 : 403, "{\"ok\":false,\"why\":\"" + no + "\"}"};
      if (announced == null || Long.parseLong(announced) != body.length) {
        return new Object[] {400, "{\"ok\":false,\"why\":\"the file arrived incomplete\"}"};
      }
      if (stored.containsKey(name) && stored.get(name).length == body.length) {
        return new Object[] {200, "{\"ok\":true,\"saved\":false,\"why\":\"already here\"}"};
      }
      puts++;
      stored.put(name, body);
      arrivals.add(q.get("kind") + " from " + q.get("device") + ": " + name);
      return new Object[] {200, "{\"ok\":true,\"saved\":true,\"name\":\"" + name + "\"}"};
    }
    return new Object[] {404, "{}"};
  }

  private String refusal(String name) {
    if (!uploads) return "uploads are switched off on this Deck";
    if (name == null || !name.matches("(?i).*\\.(jpe?g|png|heic|mp4|mov|gpx|fit|tcx|kml|json|csv|txt)")) {
      return "that kind of file is not accepted";
    }
    return null;
  }

  private static Map<String, String> query(String raw) throws IOException {
    Map<String, String> q = new LinkedHashMap<>();
    if (raw == null) return q;
    for (String pair : raw.split("&")) {
      int eq = pair.indexOf('=');
      if (eq > 0) q.put(pair.substring(0, eq), URLDecoder.decode(pair.substring(eq + 1), "UTF-8"));
    }
    return q;
  }

  /** A day on the desk: what the real Deck's /api/state holds, in miniature. */
  private String state() {
    return "{\"uploads\":" + uploads + ",\"link\":\"connected\","
        + "\"yt\":{\"ok\":true,\"subs\":\"18.4K\",\"toGoal\":6600,\"goalPct\":74,\"goal\":25000,\"day\":1240,\"daySpan\":24},"
        + "\"board\":{\"owed\":[{\"title\":\"Arctic Leopard XE Pro R: first ride\",\"pct\":60,\"status\":\"Editing the hill climbs\"},"
        + "{\"title\":\"DRAX X-Pro 72V controller upgrade\",\"pct\":20,\"status\":\"Footage offloaded\"}],"
        + "\"inflight\":[{\"title\":\"Ultra Bee range test\"}],\"blocked\":[{\"title\":\"Pick the thumbnail\"}]},"
        + "\"claude\":[{\"title\":\"Phone offload and ride upload\",\"total\":7,\"done\":5,\"working\":true,\"ago\":\"now\",\"active\":\"Testing with two phones\"},"
        + "{\"title\":\"Site parts catalog sweep\",\"total\":4,\"done\":4,\"working\":false,\"ago\":\"2h\",\"active\":\"\"}],"
        + "\"alerts\":[{\"text\":\"robolectric: 6 files received over the network (41 KB)\",\"ago\":\"now\"},"
        + "{\"text\":\"Render finished: XE Pro R first ride v3\",\"ago\":\"12m\"}],"
        + "\"vol\":{\"v\":42,\"muted\":false},\"load\":35,"
        + "\"apps\":[{\"key\":\"studio\",\"label\":\"Studio\"},{\"key\":\"ytstudio\",\"label\":\"YT Studio\"},"
        + "{\"key\":\"resolve\",\"label\":\"Resolve\"},{\"key\":\"drive\",\"label\":\"Footage\"}]}";
  }

  /** Puts the test phone on Wi-Fi, which is the only kind of network the link uses. */
  static void onWifi(Context c) {
    ConnectivityManager cm = c.getSystemService(ConnectivityManager.class);
    NetworkCapabilities caps = ShadowNetworkCapabilities.newInstance();
    Shadows.shadowOf(caps).addTransportType(NetworkCapabilities.TRANSPORT_WIFI);
    Shadows.shadowOf(cm).setNetworkCapabilities(cm.getActiveNetwork(), caps);
  }
}
