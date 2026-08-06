package family.eilertsen.rack.adapter.in.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.NoSuchElementException;

/**
 * The status codes the domain's own exceptions deserve.
 *
 * <p>The older endpoints each catch and translate by hand, which is fine until an
 * endpoint is added and does not. Typing {@code javascript:alert(1)} into the
 * link box answered <em>500 Internal Server Error</em> — the app saying it had
 * broken, when in fact it had worked exactly as intended and refused a bad
 * address. A wrong status is a lie about whose fault it is.
 *
 * <p>Explicit {@code ResponseStatusException}s still win: they are thrown, not
 * translated, so nothing already deliberate is changed by this.
 */
@RestControllerAdvice
class DomainErrors {

    /** Asked for something that is not there. */
    @ExceptionHandler(NoSuchElementException.class)
    ProblemDetail notFound(NoSuchElementException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, message(e));
    }

    /**
     * Asked for something impossible: an address that is not the web, a status
     * that does not exist, an item index past the end of the drawer.
     */
    @ExceptionHandler({IllegalArgumentException.class, IndexOutOfBoundsException.class})
    ProblemDetail badRequest(RuntimeException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, message(e));
    }

    /**
     * Asked for something reasonable that the current state forbids — deleting a
     * container that still holds items, most often. The request is not malformed,
     * so it is a conflict rather than a bad request.
     */
    @ExceptionHandler(IllegalStateException.class)
    ProblemDetail conflict(IllegalStateException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, message(e));
    }

    private static String message(RuntimeException e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }
}
