package com.maxapacity.ride;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.json.JSONArray;
import org.json.JSONObject;
import org.robolectric.Shadows;
import org.robolectric.shadows.ShadowNetworkCapabilities;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A stand-in for the Deck on this machine, answering the way deck/server.mjs
 * does for everything deck/APP-LINK.md promises a phone app: state, pairing
 * with a 6-digit code, asking which files are wanted, and taking them. The
 * real link code was also run against the real Deck; this keeps that honest
 * on every build.
 */
final class FakeDeck {

  final HttpServer server;
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
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/api/state", x -> {
      if (!letIn(x)) return;
      reply(x, 200, state());
    });
    server.createContext("/api/action", x -> {
      if (!letIn(x)) return;
      reply(x, 200, "{\"ok\":true}");
    });
    server.createContext("/api/pair", x -> {
      try {
        JSONObject b = new JSONObject(new String(x.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        if (code == null) {
          reply(x, 200, "{\"ok\":false,\"why\":\"closed\"}");
        } else if (!code.equals(b.optString("code"))) {
          reply(x, 200, "{\"ok\":false,\"why\":\"wrong\"}");
        } else {
          code = null;
          key = "k".repeat(43);
          reply(x, 200, new JSONObject().put("ok", true).put("name", b.optString("name")).put("key", key).toString());
        }
      } catch (Exception e) {
        reply(x, 200, "{\"ok\":false,\"why\":\"wrong\"}");
      }
    });
    server.createContext("/api/upload/check", x -> {
      if (!letIn(x)) return;
      try {
        JSONObject b = new JSONObject(new String(x.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
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
        reply(x, 200, new JSONObject().put("ok", true).put("need", need).put("have", have).put("refused", refused).toString());
      } catch (Exception e) {
        reply(x, 500, "{\"ok\":false,\"why\":\"bad request\"}");
      }
    });
    server.createContext("/api/upload", x -> {
      if (!letIn(x)) return;
      Map<String, String> q = query(x.getRequestURI().getRawQuery());
      String name = q.get("name");
      byte[] body = x.getRequestBody().readAllBytes();
      String no = refusal(name);
      String announced = x.getRequestHeaders().getFirst("Content-Length");
      if (no != null) {
        reply(x, uploads ? 415 : 403, "{\"ok\":false,\"why\":\"" + no + "\"}");
      } else if (announced == null || Long.parseLong(announced) != body.length) {
        reply(x, 400, "{\"ok\":false,\"why\":\"the file arrived incomplete\"}");
      } else if (stored.containsKey(name) && stored.get(name).length == body.length) {
        reply(x, 200, "{\"ok\":true,\"saved\":false,\"why\":\"already here\"}");
      } else {
        puts++;
        stored.put(name, body);
        arrivals.add(q.get("kind") + " from " + q.get("device") + ": " + name);
        reply(x, 200, "{\"ok\":true,\"saved\":true,\"name\":\"" + name + "\"}");
      }
    });
    server.start();
  }

  String address() {
    return "127.0.0.1:" + server.getAddress().getPort();
  }

  void stop() {
    server.stop(0);
  }

  private String refusal(String name) {
    if (!uploads) return "uploads are switched off on this Deck";
    if (name == null || !name.matches("(?i).*\\.(jpe?g|png|heic|mp4|mov|gpx|fit|tcx|kml|json|csv|txt)")) {
      return "that kind of file is not accepted";
    }
    return null;
  }

  /** The cable is trusted; the network wants the key this phone was handed. */
  private boolean letIn(HttpExchange x) throws IOException {
    if (!wantsKey) return true;
    String sent = x.getRequestHeaders().getFirst("x-deck-token");
    if (key != null && key.equals(sent)) return true;
    x.getRequestBody().readAllBytes();
    reply(x, 401, "{\"error\":\"not paired\"}");
    return false;
  }

  private static void reply(HttpExchange x, int code, String json) throws IOException {
    byte[] b = json.getBytes(StandardCharsets.UTF_8);
    x.getResponseHeaders().set("Content-Type", "application/json");
    x.sendResponseHeaders(code, b.length);
    try (OutputStream out = x.getResponseBody()) {
      out.write(b);
    }
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
