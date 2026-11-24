package com.neo.service.validator;

import com.neo.exception.AppException;
import java.util.List;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.web.multipart.MultipartFile;

public final class ImageFileValidator {

  public static final String INVALID_IMAGE = "INVALID_IMAGE";

  private ImageFileValidator() {}

  /** An empty list is allowed (e.g. update without new images). */
  public static void validate(List<MultipartFile> files) {
    if (files != null) {
      files.forEach(ImageFileValidator::validate);
    }
  }

  public static void validate(MultipartFile file) {
    if (file == null || file.isEmpty()) {
      throw new AppException(HttpStatus.BAD_REQUEST, INVALID_IMAGE, "Image file is empty");
    }
    String type = file.getContentType();
    if (type == null || !type.toLowerCase(Locale.ROOT).startsWith("image/")) {
      throw new AppException(
          HttpStatus.BAD_REQUEST, INVALID_IMAGE, "Only image files can be uploaded");
    }
  }

  /** Safe for a public URL: no path, only [A-Za-z0-9._-], max 100 chars. */
  public static String safeFileName(String originalFilename) {
    String name = originalFilename == null ? "" : originalFilename;
    name = name.substring(Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\')) + 1);
    name = name.replaceAll("[^A-Za-z0-9._-]", "_");
    if (name.isBlank() || name.chars().allMatch(c -> c == '.' || c == '_')) {
      name = "image";
    }
    return name.length() > 100 ? name.substring(name.length() - 100) : name;
  }
}
