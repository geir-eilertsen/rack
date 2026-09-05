package family.eilertsen.rack.domain.model;

/** A frame fitted for keeping: a JPEG unless the format could not be read, in which case the bytes as they came. */
public record FittedPhoto(byte[] bytes, String contentType) {}
