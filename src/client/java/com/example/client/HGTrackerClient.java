package com.example.hgtracker;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Hunger Games tracker (client side). Every 2 seconds it POSTs the local player's
 * position plus nearby-player and entity counts to the Discord bot.
 *
 * Only Fabric Loader is needed (no Fabric API).
 */
public class HGTrackerClient implements ClientModInitializer {
    // >>> Set this to your bot's address, then rebuild <<<
    private static final String BOT_URL = "https://i9yhkbb3c4.apps.bot-hosting.cloud/update";
    private static final int SEND_INTERVAL_SECONDS = 2;

    private static final Gson GSON = new Gson();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    @Override
    public void onInitializeClient() {
        ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "hgtracker-timer");
            t.setDaemon(true);
            return t;
        });
        // The timer only schedules work; data is read on the game thread via mc.execute().
        timer.scheduleAtFixedRate(() -> {
            try {
                Minecraft mc = Minecraft.getInstance();
                mc.execute(() -> collectAndSend(mc));
            } catch (Throwable ignored) {
            }
        }, 5, SEND_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    private void collectAndSend(Minecraft mc) {
        if (mc.player == null || mc.level == null) return;
        LocalPlayer me = mc.player;
        ClientLevel level = mc.level;

        // Other players currently loaded on this client
        JsonArray players = new JsonArray();
        for (AbstractClientPlayer p : level.players()) {
            if (p == me) continue;
            JsonObject o = new JsonObject();
            o.addProperty("name", p.getName().getString());
            o.addProperty("x", round(p.getX()));
            o.addProperty("y", round(p.getY()));
            o.addProperty("z", round(p.getZ()));
            o.addProperty("distance", round(p.distanceTo(me)));
            players.add(o);
        }

        // Entities inside this client's entity render distance
        int entitiesTotal = 0;
        int entitiesInRange = 0;
        for (Entity e : level.entitiesForRendering()) {
            entitiesTotal++;
            if (e.shouldRender(me.getX(), me.getY(), me.getZ())) entitiesInRange++;
        }

        JsonObject body = new JsonObject();
        body.addProperty("player", me.getName().getString());
        body.addProperty("x", round(me.getX()));
        body.addProperty("y", round(me.getY()));
        body.addProperty("z", round(me.getZ()));
        body.addProperty("dimension", level.dimension().identifier().toString());
        body.addProperty("health", round(me.getHealth()));
        body.add("players_visible", players);
        body.addProperty("players_rendered", players.size());
        body.addProperty("entities_loaded", entitiesTotal);
        body.addProperty("entities_rendered", entitiesInRange);

        HttpRequest req = HttpRequest.newBuilder(URI.create(BOT_URL))
                .timeout(Duration.ofSeconds(3))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body)))
                .build();
        HTTP.sendAsync(req, HttpResponse.BodyHandlers.discarding()); // never blocks the game
    }

    private static double round(double v) {
        return Math.round(v * 10.0) / 10.0;
    }
}
