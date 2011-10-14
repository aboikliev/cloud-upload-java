package com.caiofilipini.upload.handler;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.servlet.ServletConfig;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
//import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.fileupload.FileItemIterator;
import org.apache.commons.fileupload.FileItemStream;
import org.apache.commons.fileupload.FileUploadException;
import org.apache.commons.fileupload.servlet.ServletFileUpload;
import org.apache.commons.fileupload.util.Streams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.caiofilipini.upload.progress.InProgress;
import com.caiofilipini.upload.progress.UploadProgress;
import com.caiofilipini.upload.stream.UploadStream;

public class UploadHandler extends HttpServlet {

    private static final long serialVersionUID = 1L;
    private static final String FILES_PATH = "/files";
    private static Logger log = LoggerFactory.getLogger(UploadHandler.class);

    private ServletFileUpload fileUpload;
    private ServletContext servletContext;

    public UploadHandler() {
    }

    UploadHandler(ServletFileUpload fileUpload) {
        this.fileUpload = fileUpload;
    }

    @Override
    public void init(ServletConfig config) throws ServletException {
        this.servletContext = config.getServletContext();

        String path = this.servletContext.getRealPath(".") + FILES_PATH;
        File filesPath = new File(path);

        if (!filesPath.exists()) {
            log.info("Creating directory {}", path);

            filesPath.mkdir();

            log.info("Directory {} successfully created.", path);
        }
    }

    @Override
    public void doPost(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        int totalSize = request.getContentLength();
        log.info("Received {} bytes through POST.", totalSize);

        Map<String, String> params = new HashMap<String, String>();

        ServletFileUpload fileUpload = getFileUpload();
        FileItemIterator itemIterator = null;

        try {
            itemIterator = fileUpload.getItemIterator(request);

            while (itemIterator.hasNext()) {
                FileItemStream multipartField = itemIterator.next();

                if (multipartField.isFormField()) {
                    String fieldValue = Streams.asString(multipartField.openStream());
                    params.put(multipartField.getFieldName(), fieldValue);
                } else {
                    String uid = params.get("uid");

                    if (isEmpty(uid)) {
                        respond400(response);
                        return;
                    }

                    try {
                        writeStreamToDisk(totalSize, multipartField, uid, request);
                    } catch (IOException e) {
                        log.error("An error occurred while handling upload id {}", uid);

                        response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
                        InProgress.abort(uid);

                        return;
                    }
                }
            }
        } catch (FileUploadException e) {
            throw new IOException(e);
        }

        response.setStatus(HttpServletResponse.SC_OK);
    }

    private boolean isEmpty(String uid) {
        return uid == null || uid.isEmpty();
    }

    private void respond400(HttpServletResponse response) throws IOException {
        response.sendError(HttpServletResponse.SC_BAD_REQUEST);
    }

    private void writeStreamToDisk(
            int totalSize,
            FileItemStream multipartField,
            String uid,
            HttpServletRequest request) throws IOException {

        UploadProgress progress = new UploadProgress(Long.valueOf(totalSize));
        InProgress.store(uid, progress);

        String originalFileName = multipartField.getName();
        String newFilePath = newFileNameFor(uid, originalFileName);
        String webappDiskPath = this.servletContext.getRealPath(".");

        long start = System.currentTimeMillis();
        log.info("Started writing {}", newFilePath);

        new UploadStream(multipartField, progress).copyToFile(webappDiskPath, newFilePath);

        String downloadablePath = request.getContextPath() + newFilePath;
        progress.fileAvailableAt(downloadablePath);

        long end = System.currentTimeMillis();
        log.info("Finished writing {} in {} ms.", newFilePath, (end - start));
    }

    private String newFileNameFor(String uid, String originalFileName) {
        return FILES_PATH + File.separator + uid + extractExtensionFrom(originalFileName);
    }

    private String extractExtensionFrom(String name) {
        Pattern fileExtensionRegex = Pattern.compile("(\\.\\w+)$");
        Matcher fileExtensionMatcher = fileExtensionRegex.matcher(name);
        String extension = "";

        if (fileExtensionMatcher.find()) {
            extension = fileExtensionMatcher.group();
        }

        return extension;
    }

    private ServletFileUpload getFileUpload() {
        return this.fileUpload != null ? this.fileUpload : new ServletFileUpload();
    }

}
