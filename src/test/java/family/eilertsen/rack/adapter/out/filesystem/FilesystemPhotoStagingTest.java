package family.eilertsen.rack.adapter.out.filesystem;

import family.eilertsen.rack.domain.model.ContainerId;
import family.eilertsen.rack.domain.model.SlotId;
import family.eilertsen.rack.domain.model.StagedPhoto;
import family.eilertsen.rack.domain.model.FittedPhoto;
import family.eilertsen.rack.domain.port.PhotoStaging;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FilesystemPhotoStagingTest {

    @TempDir
    Path dataDir;

    @Test
    void aStagedFrameIsFittedTurnedAndRememberedWithWhereItWasShot() throws IOException {
        FilesystemPhotoStaging staging = new FilesystemPhotoStaging(dataDir.toString());
        byte[] frame = JpegsTest.withOrientation(JpegsTest.jpeg(4000, 2000, Color.RED, Color.BLUE), 6);

        StagedPhoto staged = staging.stage(frame, "image/jpeg", new ContainerId("rack"), new SlotId("A1"));

        BufferedImage photo = ImageIO.read(new ByteArrayInputStream(staging.read(staged.id())));
        assertThat(photo.getWidth()).isEqualTo(784);
        assertThat(photo.getHeight()).isEqualTo(1568);
        BufferedImage preview = ImageIO.read(new ByteArrayInputStream(staging.preview(staged.id())));
        assertThat(preview.getHeight()).isEqualTo(264);
        List<StagedPhoto> all = staging.all();
        assertThat(all).hasSize(1);
        assertThat(all.get(0).id()).isEqualTo(staged.id());
        assertThat(all.get(0).container()).isEqualTo(new ContainerId("rack"));
        assertThat(all.get(0).slot()).isEqualTo(new SlotId("A1"));
        // Beside the photographs, not among them: the sweep of unreferenced photographs must not see it.
        assertThat(dataDir.resolve("staging")).isDirectory();
        assertThat(dataDir.resolve("photos")).doesNotExist();
    }

    @Test
    void whereItWasShotMayBeNowhereYet() throws IOException {
        FilesystemPhotoStaging staging = new FilesystemPhotoStaging(dataDir.toString());
        StagedPhoto staged = staging.stage(JpegsTest.jpeg(100, 100, Color.RED, Color.BLUE), "image/jpeg", null, null);
        assertThat(staging.all().get(0).container()).isNull();
        assertThat(staging.all().get(0).slot()).isNull();
        assertThat(staged.id()).matches("[A-Za-z0-9_-]+");
    }

    @Test
    void removingTakesAllThreeFilesAndShrugsAtOneAlreadyGone() throws IOException {
        FilesystemPhotoStaging staging = new FilesystemPhotoStaging(dataDir.toString());
        StagedPhoto staged = staging.stage(JpegsTest.jpeg(100, 100, Color.RED, Color.BLUE), "image/jpeg", null, null);
        staging.remove(staged.id());
        staging.remove(staged.id());
        assertThat(staging.all()).isEmpty();
        try (var files = Files.list(dataDir.resolve("staging"))) {
            assertThat(files.toList()).isEmpty();
        }
        assertThatThrownBy(() -> staging.read(staged.id())).isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void anIdThatIsNotAPlainNameIsRefused() throws IOException {
        FilesystemPhotoStaging staging = new FilesystemPhotoStaging(dataDir.toString());
        assertThatThrownBy(() -> staging.read("../photos/x")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aBatchLeftForAWeekIsSweptAtBoot() throws IOException {
        FilesystemPhotoStaging staging = new FilesystemPhotoStaging(dataDir.toString());
        StagedPhoto old = staging.stage(JpegsTest.jpeg(100, 100, Color.RED, Color.BLUE), "image/jpeg", null, null);
        StagedPhoto fresh = staging.stage(JpegsTest.jpeg(100, 100, Color.RED, Color.BLUE), "image/jpeg", null, null);
        FileTime lastWeek = FileTime.from(Instant.now().minus(Duration.ofDays(8)));
        try (var files = Files.list(dataDir.resolve("staging"))) {
            for (Path p : files.toList()) if (p.getFileName().toString().startsWith(old.id())) Files.setLastModifiedTime(p, lastWeek);
        }

        new FilesystemPhotoStaging(dataDir.toString());

        assertThat(staging.all()).extracting(StagedPhoto::id).containsExactly(fresh.id());
    }

    @Test
    void whatItCannotReadItKeepsAsItCame() throws IOException {
        FilesystemPhotoStaging staging = new FilesystemPhotoStaging(dataDir.toString());
        byte[] heic = "not a jpeg".getBytes(StandardCharsets.UTF_8);
        FittedPhoto fitted = staging.fit(heic, "image/heic");
        assertThat(fitted.bytes()).isEqualTo(heic);
        assertThat(fitted.contentType()).isEqualTo("image/heic");
    }
}
