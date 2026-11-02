package com.example.stoploss.web;

import com.example.stoploss.auth.AppUser;
import com.example.stoploss.upload.IngestService;
import com.example.stoploss.upload.UploadQueries;
import com.example.stoploss.upload.UploadResult;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;
import org.springframework.web.util.WebUtils;

/**
 * Claim-file uploads (analyst only). The multipart body is parsed lazily ({@code resolve-lazily}), so the role check
 * runs before a broker's upload is ever read.
 */
@RestController
@RequestMapping("/api")
public class UploadController {
  private static final Logger log = LoggerFactory.getLogger(UploadController.class);

  /** Largest claim file accepted (Cloud Run caps request bodies at 32 MiB); also {@code spring.servlet.multipart}. */
  public static final int MAX_UPLOAD_MB = 25;

  private final IngestService ingest;
  private final UploadQueries uploads;

  public UploadController(IngestService ingest, UploadQueries uploads) {
    this.ingest = ingest;
    this.uploads = uploads;
  }

  @PostMapping("/uploads")
  public ResponseEntity<UploadResult> upload(HttpServletRequest request) throws IOException {
    AppUser user = AppUser.current(request).requireAnalyst();
    MultipartFile file = singleFile(request);
    if (file == null) throw new HttpError(400, "Attach the claim file in the multipart field \"file\"");

    UploadResult result = ingest.ingest(file.getOriginalFilename(), file.getBytes(), user.email());
    UploadResult.Counts c = result.counts();
    log.info("upload #{} {} by {}: {}{}", result.id(), result.filename(), result.uploadedBy(), result.status(),
        UploadResult.PROCESSED.equals(result.status())
            ? " rows=%d applied=%d unchanged=%d older=%d summary=%d errors=%d alerts=%d".formatted(c.rows(),
                c.applied(), c.unchanged(), c.olderIgnored(), c.summary(), c.errors(), result.alertsRaised().size())
            : " (" + result.rejectionReason() + ")");
    return ResponseEntity.status(UploadResult.PROCESSED.equals(result.status()) ? 201 : 400).body(result);
  }

  /** The one file in field {@code file}; null if there is none; 400 if a file came in any other field or twice. */
  private static MultipartFile singleFile(HttpServletRequest request) {
    MultipartHttpServletRequest multipart = WebUtils.getNativeRequest(request, MultipartHttpServletRequest.class);
    if (multipart == null) return null;
    MultiValueMap<String, MultipartFile> files = multipart.getMultiFileMap(); // parses the body now
    MultipartFile found = null;
    for (Map.Entry<String, List<MultipartFile>> e : files.entrySet()) {
      if (!"file".equals(e.getKey()) || e.getValue().size() > 1) {
        throw new HttpError(400, "Send exactly one file, in the multipart field \"file\"");
      }
      found = e.getValue().getFirst();
    }
    return found;
  }

  @GetMapping("/uploads")
  public List<UploadResult.Summary> list(HttpServletRequest request) {
    AppUser.current(request).requireAnalyst();
    return uploads.listUploads(200);
  }

  @GetMapping("/uploads/{id}")
  public UploadResult.Detail get(@PathVariable("id") String rawId, HttpServletRequest request) {
    AppUser.current(request).requireAnalyst();
    Long id = Params.parseId(rawId);
    UploadResult.Detail upload = id == null ? null : uploads.getUpload(id);
    if (upload == null) throw new HttpError(404, "Upload not found");
    return upload;
  }
}
