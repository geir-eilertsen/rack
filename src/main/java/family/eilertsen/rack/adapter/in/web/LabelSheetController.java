package family.eilertsen.rack.adapter.in.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

@RestController
public class LabelSheetController {

    @GetMapping("/labels")
    public ResponseEntity<byte[]> labels(HttpServletRequest req,
                                          @RequestParam(required = false) String base) throws IOException {
        String baseUrl = base != null ? base : requestBase(req);
        byte[] pdf = LabelSheet.build(baseUrl);
        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_PDF)
            .header("Content-Disposition", "inline; filename=\"drawer-labels.pdf\"")
            .body(pdf);
    }

    private static String requestBase(HttpServletRequest req) {
        String scheme = req.getScheme();
        int port = req.getServerPort();
        boolean defaultPort = (scheme.equals("http") && port == 80)
            || (scheme.equals("https") && port == 443);
        return scheme + "://" + req.getServerName() + (defaultPort ? "" : ":" + port);
    }
}
