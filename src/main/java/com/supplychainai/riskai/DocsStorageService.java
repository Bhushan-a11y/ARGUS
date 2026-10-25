package com.supplychainai.riskai;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;

@Service 
public class DocsStorageService {
    private final DocumentStorageProperties properties;
    private final Path rootPath;

    DocsStorageService(DocumentStorageProperties properties) {
        this.properties = properties;
        this.rootPath = Paths.get(properties.basePath());
    }

    public String storeDocs(InputStream inputStream, String ogName) throws IOException
    {
        LocalDate today = LocalDate.now();
        Path dateDir = rootPath.resolve(
            today.getYear() + File.separator + 
            String.format("%02d",today.getMonthValue()) + File.separator + 
            String.format("%02d",today.getDayOfMonth())
        );
        String ext = getExtension(ogName);
        String uuid = UUID.randomUUID() + (ext.isEmpty() ? "" : "."+ext);
        Path filePath = dateDir.resolve(uuid);
        Files.createDirectories(dateDir);

        try(OutputStream outputStream = Files.newOutputStream(filePath,StandardOpenOption.CREATE_NEW)) {
            StreamUtils.copy(inputStream, outputStream);
        }
        return rootPath.relativize(filePath).toString();
    }

    private String getExtension(String filename)
    {
        int lastDot = filename.lastIndexOf('.');
        return lastDot==-1 ? "" : (filename.substring(lastDot+1));
    }

    public Resource getFileAsResource(String uuid) throws IOException
    {
        Path filePath = rootPath.resolve(uuid).normalize().toAbsolutePath();
        Path allowedPath = rootPath.normalize().toAbsolutePath();
        if(!filePath.startsWith(allowedPath))
        {
            throw new SecurityException("Access Denied !!!!!");
        }

        if(!Files.exists(filePath))
        {
            throw new FileNotFoundException("Requested File not found");
        }
        return new UrlResource(filePath.toUri());

    }

}
