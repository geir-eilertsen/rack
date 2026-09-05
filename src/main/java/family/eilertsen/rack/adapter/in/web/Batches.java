package family.eilertsen.rack.adapter.in.web;

import family.eilertsen.rack.application.AddPhotoToSlot;
import family.eilertsen.rack.domain.model.FittedPhoto;
import family.eilertsen.rack.domain.port.PhotoStaging;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * A batch of photographs arrives one of two ways, and every endpoint that
 * takes one takes both: repeated {@code photo} parts carrying the frames
 * themselves, or repeated {@code staged} ids naming frames already uploaded
 * the moment they were shot. The parts are fitted here, so a full camera
 * frame sent straight from a file input is never handed to the model at
 * 50 megapixels; a staged one was fitted when it was staged.
 */
@Component
public class Batches {

    private final PhotoStaging staging;

    public Batches(PhotoStaging staging) {
        this.staging = staging;
    }

    public List<AddPhotoToSlot.Photo> photos(List<MultipartFile> parts, List<String> staged) throws IOException {
        List<AddPhotoToSlot.Photo> batch = new ArrayList<>();
        for (MultipartFile part : orEmpty(parts)) {
            FittedPhoto fitted = staging.fit(part.getBytes(), part.getContentType());
            batch.add(new AddPhotoToSlot.Photo(fitted.bytes(), fitted.contentType()));
        }
        for (String id : orEmpty(staged)) batch.add(new AddPhotoToSlot.Photo(read(id), "image/jpeg"));
        if (batch.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No photos: send photo parts or staged ids");
        return batch;
    }

    public List<byte[]> bytes(List<MultipartFile> parts, List<String> staged) throws IOException {
        return photos(parts, staged).stream().map(AddPhotoToSlot.Photo::bytes).toList();
    }

    /** Once a batch is filed, the staged copies are spoken for and go. */
    public void release(List<String> staged) {
        for (String id : orEmpty(staged)) staging.remove(id);
    }

    private byte[] read(String id) {
        try {
            return staging.read(id);
        } catch (NoSuchElementException gone) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, gone.getMessage(), gone);
        } catch (IllegalArgumentException bad) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, bad.getMessage(), bad);
        }
    }

    private static <T> List<T> orEmpty(List<T> list) {
        return list == null ? List.of() : list;
    }
}
