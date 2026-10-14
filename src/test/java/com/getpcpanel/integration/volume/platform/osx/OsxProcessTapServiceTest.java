package com.getpcpanel.integration.volume.platform.osx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import javax.annotation.Nullable;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.getpcpanel.integration.volume.platform.MuteType;
import com.getpcpanel.util.os.FakeProcess;

/**
 * The tap bookkeeping of {@link OsxProcessTapService} against a fake CoreAudio: taps follow the default output,
 * keep their volume and mute, and recover from a tap that cannot be created. Runs on every OS. The two "apps" are
 * real, idle child processes, because taps are scoped to process lifetimes.
 */
class OsxProcessTapServiceTest {
    private static final int SPEAKERS = 1;
    private static final int HEADPHONES = 2;
    private static final int MONITOR = 3;
    private Process firefoxProcess;
    private Process musicProcess;
    private int firefox;
    private int music;

    private FakeCoreAudio coreAudio;
    private FakeTaps factory;
    private OsxProcessTapService service;

    @BeforeAll
    void startApps() throws IOException {
        firefoxProcess = new ProcessBuilder(FakeProcess.command("idle")).start();
        musicProcess = new ProcessBuilder(FakeProcess.command("idle")).start();
        firefox = (int) firefoxProcess.pid();
        music = (int) musicProcess.pid();
    }

    @AfterAll
    void stopApps() {
        firefoxProcess.destroyForcibly();
        musicProcess.destroyForcibly();
    }

    @BeforeEach
    void setUp() {
        coreAudio = new FakeCoreAudio();
        factory = new FakeTaps();
        service = service(Duration.ofMillis(20));
    }

    @AfterEach
    void tearDown() {
        factory.release();
        service.destroy();
    }

    private OsxProcessTapService service(Duration retryDelay) {
        return new OsxProcessTapService(coreAudio, factory, true, retryDelay);
    }

    @Test
    void movesEveryTapToTheNewDefaultOutputKeepingVolumeAndMute() {
        service.setVolume(firefox, 0.3f);
        service.setVolume(music, 0.6f);
        service.mute(music, MuteType.mute);

        coreAudio.defaultOutput = HEADPHONES;
        assertTrue(service.migrateToDefaultOutput());

        var firefoxTap = factory.live(firefox);
        assertEquals(uid(HEADPHONES), firefoxTap.uid);
        assertEquals(0.3f, firefoxTap.gain);
        assertFalse(firefoxTap.muted);
        var musicTap = factory.live(music);
        assertEquals(uid(HEADPHONES), musicTap.uid);
        assertEquals(0.6f, musicTap.gain);
        assertTrue(musicTap.createdMuted, "the moved tap is muted before it renders");
        assertEquals(2, factory.liveCount(), "the taps on the speakers are closed");
    }

    @Test
    void aMutedAppIsTappedMutedFromTheStart() {
        service.mute(firefox, MuteType.mute);

        var tap = factory.live(firefox);
        assertTrue(tap.createdMuted, "muted before the tap renders");
        assertEquals(1f, tap.gain);
    }

    @Test
    void leavesTapsAloneWhileTheDefaultOutputStays() {
        service.setVolume(firefox, 0.3f);

        assertTrue(service.migrateToDefaultOutput());

        assertEquals(1, factory.created.size());
        assertEquals(uid(SPEAKERS), factory.live(firefox).uid);
    }

    @Test
    void keepsTapsWhileTheDefaultOutputCannotBeRead() {
        service.setVolume(firefox, 0.3f);

        coreAudio.defaultOutput = 0;

        assertFalse(service.migrateToDefaultOutput(), "not settled, so it is retried");
        assertEquals(1, factory.created.size());
        assertEquals(uid(SPEAKERS), factory.live(firefox).uid);
    }

    @Test
    void movesOnceTheDefaultOutputCanBeReadAgain() {
        service.setVolume(firefox, 0.3f);
        coreAudio.defaultOutput = 0;
        service.defaultOutputChanged();

        coreAudio.defaultOutput = HEADPHONES;

        await(() -> factory.liveOn(firefox, HEADPHONES));
        assertEquals(0.3f, factory.live(firefox).gain);
    }

    @Test
    void keepsTheOldTapWhileTheNewOutputCannotBeResolved() {
        service.setVolume(firefox, 0.3f);
        coreAudio.withoutUid.add(HEADPHONES);
        coreAudio.defaultOutput = HEADPHONES;

        assertFalse(service.migrateToDefaultOutput());
        assertEquals(1, factory.created.size(), "the old tap was never closed");
        assertTrue(factory.liveOn(firefox, SPEAKERS));

        service.defaultOutputChanged();
        coreAudio.withoutUid.clear();
        await(() -> factory.liveOn(firefox, HEADPHONES));
    }

    @Test
    void notificationMovesTapsOffTheNotifyingThread() throws InterruptedException {
        service.setVolume(firefox, 0.3f);
        factory.block();

        coreAudio.defaultOutput = HEADPHONES;
        service.defaultOutputChanged();

        assertTrue(factory.entered.await(5, TimeUnit.SECONDS), "the move started");
        assertNotEquals(Thread.currentThread(), factory.blockedThread, "the move runs on its own thread");
        factory.release();
        await(() -> factory.liveOn(firefox, HEADPHONES));
        assertEquals(0.3f, factory.live(firefox).gain);
    }

    @Test
    void rapidSwitchingEndsOnTheLastOutputWithoutLeakingTaps() throws InterruptedException {
        service.setVolume(firefox, 0.3f);
        service.setVolume(music, 0.6f);
        factory.block();

        coreAudio.defaultOutput = HEADPHONES;
        service.defaultOutputChanged();
        assertTrue(factory.entered.await(5, TimeUnit.SECONDS), "the first move started");
        for (var output : List.of(MONITOR, SPEAKERS, HEADPHONES, MONITOR)) {
            coreAudio.defaultOutput = output;
            service.defaultOutputChanged();
        }
        factory.release();

        await(() -> factory.liveOn(firefox, MONITOR) && factory.liveOn(music, MONITOR));
        assertEquals(2, factory.liveCount(), "one tap per app, every other one closed");
        // Initial taps, the move in progress (up to both apps) and a single follow-up for all changes queued meanwhile.
        assertTrue(factory.created.size() <= 6, "changes during a move are coalesced, created " + factory.created.size());
    }

    @Test
    void aTapThatCannotBeMovedStaysOnItsPreviousOutputUntilARetryMovesIt() {
        service.setVolume(music, 0.6f);
        service.mute(music, MuteType.mute);
        factory.failingUids.add(uid(HEADPHONES));

        coreAudio.defaultOutput = HEADPHONES;
        assertFalse(service.migrateToDefaultOutput());
        var kept = factory.live(music);
        assertEquals(uid(SPEAKERS), kept.uid, "back on the previous output");
        assertEquals(0.6f, kept.gain);
        assertTrue(kept.createdMuted, "still muted, never untapped for longer than the move");

        service.defaultOutputChanged();
        factory.failingUids.clear();
        await(() -> factory.liveOn(music, HEADPHONES));
        assertEquals(0.6f, factory.live(music).gain);
        assertTrue(factory.live(music).muted);
    }

    @Test
    void anAppWithNoOutputToTapKeepsItsStateUntilARetryTapsItAgain() {
        service.setVolume(music, 0.6f);
        service.mute(music, MuteType.mute);
        factory.failingUids.addAll(List.of(uid(SPEAKERS), uid(HEADPHONES)));

        coreAudio.defaultOutput = HEADPHONES;
        assertFalse(service.migrateToDefaultOutput());
        assertEquals(0, factory.liveCount());

        service.defaultOutputChanged();
        factory.failingUids.clear();
        await(() -> factory.liveOn(music, HEADPHONES));
        assertEquals(0.6f, factory.live(music).gain);
        assertTrue(factory.live(music).createdMuted);
    }

    @Test
    void aHelperThatEndsWhileItsTapMovesGetsNoTap() throws Exception {
        // A helper process (such as a WebKit audio process) of an app that keeps running: only the app is watched.
        var helper = new ProcessBuilder(FakeProcess.command("idle")).start();
        try {
            var pid = (int) helper.pid();
            service.setApplicationVolume(music, List.of(pid), 0.3f);
            factory.beforeCreate = () -> {
                helper.destroyForcibly();
                try {
                    helper.waitFor(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            };

            coreAudio.defaultOutput = HEADPHONES;
            service.migrateToDefaultOutput();

            assertEquals(2, factory.created.stream().filter(t -> t.pid == pid).count(), "the move created a tap");
            assertTrue(factory.created.stream().filter(t -> t.pid == pid).allMatch(t -> t.closed), "and closed it again");
        } finally {
            factory.beforeCreate = null;
            helper.destroyForcibly();
        }
    }

    @Test
    void stopsRetryingAfterAFewAttemptsUntilTheNextChange() throws InterruptedException {
        service.setVolume(firefox, 0.3f);
        factory.failingUids.add(uid(HEADPHONES));

        coreAudio.defaultOutput = HEADPHONES;
        service.defaultOutputChanged();
        await(() -> factory.attempts(HEADPHONES) == 1 + OsxProcessTapService.MAX_RETRIES);
        Thread.sleep(300);
        assertEquals(1 + OsxProcessTapService.MAX_RETRIES, factory.attempts(HEADPHONES), "retries are bounded");
        assertTrue(factory.liveOn(firefox, SPEAKERS));

        factory.failingUids.clear();
        coreAudio.defaultOutput = MONITOR;
        service.defaultOutputChanged();
        await(() -> factory.liveOn(firefox, MONITOR));
        assertEquals(0.3f, factory.live(firefox).gain);
    }

    @Test
    void aNewChangeReplacesTheRetriesOfThePreviousOne() throws InterruptedException {
        service.destroy();
        service = service(Duration.ofMillis(150));
        service.setVolume(firefox, 0.3f);
        factory.failingUids.addAll(List.of(uid(HEADPHONES), uid(MONITOR)));

        coreAudio.defaultOutput = HEADPHONES;
        service.defaultOutputChanged();
        await(() -> factory.attempts(HEADPHONES) == 1);
        coreAudio.defaultOutput = MONITOR;
        service.defaultOutputChanged();

        await(() -> factory.attempts(MONITOR) == 1 + OsxProcessTapService.MAX_RETRIES);
        Thread.sleep(500);
        assertEquals(1 + OsxProcessTapService.MAX_RETRIES, factory.attempts(MONITOR), "only the newest change's retries ran");
        assertEquals(1, factory.attempts(HEADPHONES));
    }

    @Test
    void usingTheControlRecoversATapThatCouldNotBeMoved() {
        service.setVolume(firefox, 0.3f);
        service.mute(firefox, MuteType.mute);
        factory.failingUids.add(uid(HEADPHONES));
        coreAudio.defaultOutput = HEADPHONES;
        service.migrateToDefaultOutput();

        factory.failingUids.clear();
        service.setVolume(firefox, 0.5f);

        var tap = factory.live(firefox);
        assertEquals(uid(HEADPHONES), tap.uid);
        assertEquals(0.5f, tap.gain);
        assertTrue(tap.createdMuted, "the mute survived the failed move");
    }

    @Test
    void fullVolumeForgetsATapThatCouldNotBeMoved() {
        service.setVolume(firefox, 0.3f);
        factory.failingUids.addAll(List.of(uid(SPEAKERS), uid(HEADPHONES)));
        coreAudio.defaultOutput = HEADPHONES;
        service.migrateToDefaultOutput();

        service.setVolume(firefox, 1);
        factory.failingUids.clear();
        coreAudio.defaultOutput = MONITOR;

        assertTrue(service.migrateToDefaultOutput());
        assertEquals(0, factory.liveCount());
        assertEquals(1, factory.created.size(), "nothing is tapped again");
    }

    @Test
    void volumeAndMuteFollowTheNewOutputWithoutANotification() {
        service.setVolume(firefox, 0.3f);
        coreAudio.defaultOutput = HEADPHONES;

        service.setVolume(firefox, 0.4f);

        assertTrue(factory.liveOn(firefox, HEADPHONES));
        assertEquals(0.4f, factory.live(firefox).gain);
        assertEquals(1, factory.liveCount());
    }

    @Test
    void aDialChangeDuringAMoveEndsOnTheNewOutputWithTheNewVolume() throws InterruptedException {
        service.setVolume(firefox, 0.3f);
        service.setVolume(music, 0.6f);
        factory.block();
        coreAudio.defaultOutput = HEADPHONES;
        service.defaultOutputChanged();
        assertTrue(factory.entered.await(5, TimeUnit.SECONDS), "the move started");

        var dial = Thread.ofPlatform().start(() -> service.setVolume(music, 0.2f));
        factory.release();
        dial.join(5_000);

        await(() -> factory.liveOn(firefox, HEADPHONES) && factory.liveOn(music, HEADPHONES));
        assertEquals(2, factory.liveCount());
        assertEquals(0.2f, factory.live(music).gain);
        assertEquals(0.3f, factory.live(firefox).gain);
    }

    @Test
    void shutdownDuringAMoveLeavesNoTapOpen() throws InterruptedException {
        service.setVolume(firefox, 0.3f);
        service.setVolume(music, 0.6f);
        factory.block();
        coreAudio.defaultOutput = HEADPHONES;
        service.defaultOutputChanged();
        assertTrue(factory.entered.await(5, TimeUnit.SECONDS), "the move started");

        var shutdown = Thread.ofPlatform().start(service::destroy);
        Thread.sleep(50);
        factory.release();
        shutdown.join(5_000);

        assertFalse(shutdown.isAlive());
        assertEquals(0, factory.liveCount(), "every tap created during the move was closed");
    }

    @Test
    void nothingIsTappedAfterShutdown() {
        service.destroy();

        service.setVolume(firefox, 0.3f);
        service.mute(music, MuteType.mute);
        service.defaultOutputChanged();

        assertTrue(factory.created.isEmpty());
    }

    @Test
    void doesNothingWhereProcessTapsAreUnsupported() throws InterruptedException {
        var unsupported = new OsxProcessTapService(coreAudio, factory, false, Duration.ofMillis(20));
        try {
            unsupported.setVolume(firefox, 0.3f);
            coreAudio.defaultOutput = HEADPHONES;
            unsupported.defaultOutputChanged();
            Thread.sleep(100);
            assertTrue(factory.created.isEmpty());
        } finally {
            unsupported.destroy();
        }
    }

    private static String uid(int deviceId) {
        return "device-" + deviceId;
    }

    private static void await(BooleanSupplier condition) {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean()) {
            assertTrue(System.nanoTime() < deadline, "timed out waiting for the taps to settle");
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
    }

    /** CoreAudio with a settable default output; every process plays audio, process object = pid + 1000. */
    private static final class FakeCoreAudio extends CoreAudioWrapper {
        volatile int defaultOutput = SPEAKERS;
        final Set<Integer> withoutUid = ConcurrentHashMap.newKeySet();

        @Override
        public int getDefaultDevice(boolean output) {
            return defaultOutput;
        }

        @Override
        public @Nullable String deviceUid(int deviceId) {
            return withoutUid.contains(deviceId) ? null : uid(deviceId);
        }

        @Override
        public int translatePidToProcessObject(int pid) {
            return pid + 1000;
        }
    }

    private static final class FakeTap implements Tap {
        final int pid;
        final String uid;
        final boolean createdMuted;
        volatile float gain;
        volatile boolean muted;
        volatile boolean closed;

        FakeTap(int pid, String uid, float gain, boolean muted) {
            this.pid = pid;
            this.uid = uid;
            this.gain = gain;
            this.muted = muted;
            createdMuted = muted;
        }

        @Override
        public void setGain(float gain) {
            this.gain = gain;
        }

        @Override
        public float gain() {
            return gain;
        }

        @Override
        public void setMuted(boolean muted) {
            this.muted = muted;
        }

        @Override
        public boolean muted() {
            return muted;
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    /** Records every tap; can refuse taps on some outputs, and hold a creation until released. */
    private static final class FakeTaps implements Tap.Factory {
        final List<FakeTap> created = new CopyOnWriteArrayList<>();
        final Set<String> failingUids = ConcurrentHashMap.newKeySet();
        final Map<String, AtomicInteger> attempts = new ConcurrentHashMap<>();
        final CountDownLatch entered = new CountDownLatch(1);
        volatile @Nullable Thread blockedThread;
        volatile @Nullable Runnable beforeCreate;
        private volatile CountDownLatch gate = new CountDownLatch(0);

        void block() {
            gate = new CountDownLatch(1);
        }

        void release() {
            gate.countDown();
        }

        @Override
        public Tap create(int processObject, String outputDeviceUid, float initialGain, boolean initialMuted) {
            var hook = beforeCreate;
            if (hook != null) {
                hook.run();
            }
            if (gate.getCount() > 0) {
                blockedThread = Thread.currentThread();
                entered.countDown();
                try {
                    gate.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            attempts.computeIfAbsent(outputDeviceUid, uid -> new AtomicInteger()).incrementAndGet();
            if (failingUids.contains(outputDeviceUid)) {
                throw new ProcessTap.TapCreationException("AudioHardwareCreateAggregateDevice", 0x21646576);
            }
            var tap = new FakeTap(processObject - 1000, outputDeviceUid, initialGain, initialMuted);
            created.add(tap);
            return tap;
        }

        int attempts(int deviceId) {
            var count = attempts.get(uid(deviceId));
            return count == null ? 0 : count.get();
        }

        FakeTap live(int pid) {
            var live = created.stream().filter(t -> t.pid == pid && !t.closed).toList();
            assertEquals(1, live.size(), "one live tap for pid " + pid);
            return live.getFirst();
        }

        boolean liveOn(int pid, int deviceId) {
            return created.stream().anyMatch(t -> t.pid == pid && !t.closed && t.uid.equals(uid(deviceId)));
        }

        long liveCount() {
            return created.stream().filter(t -> !t.closed).count();
        }
    }
}
