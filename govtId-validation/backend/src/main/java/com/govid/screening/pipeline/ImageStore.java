package com.govid.screening.pipeline;

import com.mongodb.client.gridfs.model.GridFSFile;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.gridfs.GridFsOperations;
import org.springframework.data.mongodb.gridfs.GridFsTemplate;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

import static org.springframework.data.mongodb.core.query.Criteria.where;
import static org.springframework.data.mongodb.core.query.Query.query;

/**
 * Stores the evidence images behind a case in GridFS.
 *
 * <p>The images are the primary evidence: a finding that says a photograph was replaced is
 * worth nothing if the image it was derived from is gone. They are kept alongside the case
 * so the whole screening can be reconstructed and re-run during an investigation.
 */
@Component
public class ImageStore {

    private final GridFsTemplate gridFsTemplate;
    private final GridFsOperations gridFsOperations;

    public ImageStore(GridFsTemplate gridFsTemplate, GridFsOperations gridFsOperations) {
        this.gridFsTemplate = gridFsTemplate;
        this.gridFsOperations = gridFsOperations;
    }

    /** @return the stored file id, or {@code null} when there was nothing to store. */
    public String store(byte[] bytes, String filename, String contentType) {
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        try (InputStream stream = new ByteArrayInputStream(bytes)) {
            ObjectId id = gridFsTemplate.store(stream, filename, contentType);
            return id.toHexString();
        } catch (IOException e) {
            throw new IllegalStateException("Could not store evidence image " + filename, e);
        }
    }

    public StoredImage load(String id) {
        GridFSFile file = gridFsTemplate.findOne(query(where("_id").is(new ObjectId(id))));
        if (file == null) {
            return null;
        }
        try (InputStream stream = gridFsOperations.getResource(file).getInputStream()) {
            String contentType = file.getMetadata() == null
                    ? "application/octet-stream"
                    : String.valueOf(file.getMetadata().get("_contentType"));
            return new StoredImage(stream.readAllBytes(), contentType, file.getFilename());
        } catch (IOException e) {
            throw new IllegalStateException("Could not read evidence image " + id, e);
        }
    }

    public record StoredImage(byte[] bytes, String contentType, String filename) {
    }
}
