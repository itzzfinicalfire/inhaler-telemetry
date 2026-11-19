package dev.forensic.telemetry;

import dev.forensic.telemetry.observe.CombatListener;
import dev.forensic.telemetry.observe.ObserverTask;
import dev.forensic.telemetry.observe.PaperPlayerSource;
import dev.forensic.telemetry.observe.TrackedPlayers;
import dev.forensic.telemetry.record.ControlKind;
import dev.forensic.telemetry.record.RecordEncoder;
import dev.forensic.telemetry.report.ConsoleReporter;
import dev.forensic.telemetry.ring.GapTracker;
import dev.forensic.telemetry.ring.TelemetryRing;
import dev.forensic.telemetry.upload.AsyncUploader;
import dev.forensic.telemetry.upload.HttpUploadEndpoint;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Plugin bootstrap.
 *
 * <p>This plugin is strictly report-only. It observes authoritative server state and uploads
 * telemetry to an out-of-process analysis tier. It contains no code path to any mutating Bukkit
 * API: no damage, no teleport, no attribute change, no packet cancellation, no inventory
 * mutation, no kick and no ban. That property is the load-bearing design constraint of the whole
 * system, so the bootstrap enforces the parts that are cheap to enforce here (see
 * {@link #assertReportOnly()}).
 */
public final class TelemetryPlugin extends JavaPlugin {

    private TelemetryConfig config;
    private TelemetryRing ring;
    private TrackedPlayers tracked;
    private AsyncUploader uploader;
    private UUID sessionEpoch;
    private int observerTaskId = -1;
    private int consoleReporterTaskId = -1;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.config = TelemetryConfig.load(getDataFolder().toPath().resolve("config.yml"));
        config.validate();
        assertReportOnly();

        if (!config.enabled()) {
            getLogger().info("forensic-telemetry installed but telemetry.enabled=false: observing nothing");
            return;
        }

        GapTracker gaps = new GapTracker();
        this.ring = new TelemetryRing(config.bufferBytes(), gaps);
        this.tracked = new TrackedPlayers();
        for (org.bukkit.entity.Player player : Bukkit.getOnlinePlayers()) {
            tracked.add(player);
        }
        this.sessionEpoch = ObserverTask.newSessionEpoch();
        long sessionStartMs = System.currentTimeMillis();

        PaperPlayerSource source = new PaperPlayerSource();
        ObserverTask observer =
                new ObserverTask(config, ring, tracked, source, Bukkit::getCurrentTick);
        getServer().getPluginManager().registerEvents(new CombatListener(ring, tracked), this);
        this.observerTaskId =
                getServer().getScheduler().runTaskTimer(this, observer, 1L, 1L).getTaskId();

        int startTick = Bukkit.getCurrentTick();
        ring.offerSessionStart(startTick);
        // Pre-start history is unknown, not empty: the boundary gap makes that explicit
        // from tick 0 of this session rather than letting the cloud infer silence.
        gaps.noteDroppedRange(
                0, Math.max(0, startTick - 1), ControlKind.GAP_SESSION_START, 0);

        byte[] secret = config.keySecret().getBytes(StandardCharsets.UTF_8);
        this.uploader =
                new AsyncUploader(
                        config,
                        ring,
                        new HttpUploadEndpoint(config.endpointUrl(), Duration.ofSeconds(10)),
                        tracked::uuidForEntityId,
                        config.serverId(),
                        sessionEpoch,
                        sessionStartMs,
                        secret);
        java.util.Arrays.fill(secret, (byte) 0);
        uploader.start();

        if (config.consoleReportEnabled()) {
            ConsoleReporter reporter = new ConsoleReporter(
                    getLogger(),
                    config.consoleReportUrl(),
                    config.consoleReportToken(),
                    config.serverId(),
                    sessionEpoch.toString(),
                    Duration.ofSeconds(10));
            long intervalTicks = config.consoleReportIntervalSeconds() * 20L;
            this.consoleReporterTaskId = getServer()
                    .getScheduler()
                    .runTaskTimerAsynchronously(this, reporter, intervalTicks, intervalTicks)
                    .getTaskId();
            getLogger().info(
                    "console shadow report enabled: " + config.consoleReportUrl()
                            + " every " + config.consoleReportIntervalSeconds()
                            + "s (SHADOW_ONLY, read-only)");
        }

        getLogger().info(
                "forensic-telemetry enabled: session=" + sessionEpoch
                        + ", buffer=" + config.bufferBytes()
                        + " bytes, recordSchema=v" + config.recordSchemaVersion()
                        + ", envelopeSchema=v" + config.envelopeSchemaVersion());
    }

    @Override
    public void onDisable() {
        if (uploader == null) {
            getLogger().info("forensic-telemetry disabled (was never enabled)");
            return;
        }
        if (observerTaskId >= 0) {
            getServer().getScheduler().cancelTask(observerTaskId);
        }
        if (consoleReporterTaskId >= 0) {
            getServer().getScheduler().cancelTask(consoleReporterTaskId);
        }
        // Best-effort clean-shutdown marker. If the ring cannot take it, the next
        // SESSION_START boundary covers the hole; a missing marker is itself data.
        ring.offerSessionEnd(Bukkit.getCurrentTick());
        uploader.stopAndFlush(5000);
        uploader = null;
        getLogger().info(
                "forensic-telemetry disabled: session=" + sessionEpoch
                        + " droppedState=" + ring.droppedState()
                        + " droppedControl=" + ring.droppedControl());
    }

    public TelemetryConfig config() {
        return Objects.requireNonNull(config, "config loaded during onEnable");
    }

    /**
     * Guards the report-only invariant against future edits.
     *
     * <p>The full proof of this property is code review plus {@code
     * TelemetryPluginArchitectureTest}, which scans the compiled plugin jar for references to
     * mutating Bukkit API surfaces. This check is the cheap runtime half: it fails loudly at
     * startup if the configuration asks for anything that would imply server-side action.
     */
    private void assertReportOnly() {
        if (config.reportOnlyViolations().size() > 0) {
            throw new IllegalStateException(
                    "report-only invariant violated by configuration keys: "
                            + config.reportOnlyViolations());
        }
    }

    /** Absolute path to the plugin's private directory. Telemetry is never written to disk. */
    public Path dataFolderPath() {
        return getDataFolder().toPath();
    }
}