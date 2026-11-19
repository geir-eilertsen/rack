package family.eilertsen.rack.adapter.out.filesystem;

import java.io.IOException;

/** A real JPEG for tests outside this package. */
public final class FilesystemShoppingListTestSupport {
    private FilesystemShoppingListTestSupport() {}

    public static byte[] jpeg() throws IOException {
        return FilesystemShoppingListTest.jpeg();
    }
}
