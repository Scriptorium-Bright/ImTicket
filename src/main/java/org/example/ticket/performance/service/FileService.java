package org.example.ticket.performance.service;

import org.apache.commons.io.FilenameUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static java.nio.file.Paths.get;

@Service
public class FileService {

    @Value(value = "${ticket.upload.path}") // 설정 파일에서 값 주입
    private String fileUploadDir;

    public String saveImages(MultipartFile multipartFile) throws IOException {
        if (multipartFile.isEmpty()) {
            return null;
        }

        String originalImage = multipartFile.getOriginalFilename();

        if (originalImage == null || originalImage.isEmpty()) {
            throw new IOException("image is not exist !");
        }

        String extension = FilenameUtils.getExtension(originalImage).toLowerCase();

        List<String> allowedExtensions = List.of("jpg", "jpeg", "png");
        if (!allowedExtensions.contains(extension)) {
            throw new IOException("Invalid file type: " + extension);
        }

        String imageName = UUID.randomUUID() + "." + extension;

        Path imagePath = get(fileUploadDir, "picture");
        Path destinationImagePath = imagePath.resolve(imageName);

        Files.createDirectories(destinationImagePath.getParent());

        Files.write(destinationImagePath, multipartFile.getBytes());

        if (!destinationImagePath.startsWith(imagePath.normalize())) {
            throw new IOException("Invalid file path constructed (potential path traversal attempt).");
        }




        return "/uploads/picture/" + imageName;
    }
}
