package at.techtown.sonoscontroller;

import org.junit.Test;
import java.io.File;
import java.io.Closeable;
import java.util.List;
import static org.junit.Assert.*;

/** This fixture is compiled by javac, not Kotlin; it guards the consumer-facing Java API. */
public class JavaApiCompileTest {
    @Test public void javaCanConstructSourcesAndMetadata() {
        AudioSource.Url url = new AudioSource.Url("https://example.test/song.mp3");
        AudioSource.LocalFile file = new AudioSource.LocalFile(new File("song.mp3"), "audio/mpeg");
        SonosMetadata metadata = new SonosMetadata("Titel", "Interpret", null);
        assertEquals("https://example.test/song.mp3", url.getUrl());
        assertEquals("audio/mpeg", file.getMimeType());
        assertEquals("Titel", metadata.getTitle());
    }

    // Compile-only fixture: never performs networking.
    public static void useController(SonosControllerJava controller, SonosDevice device,
                                     SonosControllerJava.Callback<Void> done,
                                     SonosControllerJava.Callback<List<SonosDevice>> found) {
        controller.discover(found);
        controller.play(device, new AudioSource.Url("https://example.test/song.mp3"), done);
        controller.pause(device, done);
        controller.resume(device, done);
        controller.seekTo(device, 60, done);
        controller.seekBy(device, -10, done);
        controller.volumeUp(device, done);
        controller.volumeDown(device, done, 5);
        controller.setVolume(device, 20, done);
        Closeable listener = controller.observe(state -> assertNotNull(state));
        controller.endSession(done);
        controller.close(done);
    }
}
