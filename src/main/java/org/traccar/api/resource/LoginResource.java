/*
 * Copyright 2026 - Traccar fork customization (login page background files)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.traccar.api.resource;

import org.traccar.api.BaseResource;
import org.traccar.config.Config;
import org.traccar.storage.StorageException;

import jakarta.annotation.security.PermitAll;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.StreamingOutput;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Storage for the login page background media (PNG / JPG / GIF / MP4)
 * selected by the administrator in the web client (Settings / Server,
 * section "تنظیمات login").
 *
 * The web client stores the selected file names in the server attributes:
 *   login.paper.windows.file    background of the login Paper (desktop)
 *   login.paper.mobile.file     background of the login Paper (mobile)
 *   login.sidebar.windows.file  background of the login sidebar (desktop)
 *   login.sidebar.mobile.file   background of the login sidebar (mobile)
 * and the sidebar visibility switch in:
 *   login.sidebar               true when the login sidebar is displayed
 * Those attributes are served to the login page through the standard public
 * "api/server" endpoint (PermitAll), so they are available before signing in.
 *
 * Endpoints:
 *   POST   /api/server/login          list (admin only)
 *   GET    /api/server/login/{name}   download (public, @PermitAll) so the
 *                                     login page can display the background
 *                                     before authentication
 *   POST   /api/server/login/{name}   upload (admin only)
 *   DELETE /api/server/login/{name}   remove (admin only)
 *
 * Files are stored in the directory configured with "login.path" in
 * traccar.xml (default: ./data/login):
 *   <entry key='login.path'>./data/login</entry>
 *
 * Downloaded files carry long-lived caching headers; every upload gets a new
 * timestamped name on the client side, so replacing a file automatically
 * busts the browser cache.
 */
@Path("server/login")
@Produces(MediaType.APPLICATION_JSON)
public class LoginResource extends BaseResource {

    private static final String DEFAULT_LOGIN_PATH = "./data/login";

    private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");

    private static final long MAX_FILE_SIZE = 25 * 1024 * 1024; // 25 MB

    private static final List<String> ALLOWED_EXTENSIONS = List.of(".png", ".jpg", ".jpeg", ".gif", ".mp4");

    @Inject
    private Config config;

    private File getLoginDirectory() throws IOException {
        String path = config.getString("login.path");
        if (path == null || path.isEmpty()) {
            path = DEFAULT_LOGIN_PATH;
        }
        File directory = new File(path);
        Files.createDirectories(directory.toPath());
        return directory.getCanonicalFile();
    }

    private static boolean isAllowedExtension(String name) {
        String lower = name.toLowerCase(Locale.US);
        return ALLOWED_EXTENSIONS.stream().anyMatch(lower::endsWith);
    }

    private static String contentType(String name) {
        String lower = name.toLowerCase(Locale.US);
        if (lower.endsWith(".png")) {
            return "image/png";
        }
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (lower.endsWith(".gif")) {
            return "image/gif";
        }
        return "video/mp4";
    }

    private File checkFile(String name) throws IOException {
        if (name == null || !SAFE_NAME.matcher(name).matches() || !isAllowedExtension(name)) {
            throw new WebApplicationException(Response.status(Response.Status.NOT_FOUND).build());
        }
        File directory = getLoginDirectory();
        File file = new File(directory, name).getCanonicalFile();
        if (!file.getPath().startsWith(directory.getPath() + File.separator)) {
            throw new WebApplicationException(Response.status(Response.Status.NOT_FOUND).build());
        }
        return file;
    }

    @GET
    public List<Map<String, Object>> get() throws IOException, StorageException {
        permissionsService.checkAdmin(getUserId());

        List<Map<String, Object>> result = new java.util.ArrayList<>();
        File[] files = getLoginDirectory().listFiles((dir, name) -> isAllowedExtension(name));
        if (files != null) {
            Arrays.sort(files, Comparator.comparingLong(File::lastModified).reversed());
            for (File file : files) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("name", file.getName());
                item.put("size", file.length());
                item.put("date", new java.util.Date(file.lastModified()));
                result.add(item);
            }
        }
        return result;
    }

    @GET
    @Path("{name}")
    @PermitAll
    public Response download(@PathParam("name") String name) throws IOException {
        File file = checkFile(name);

        if (!file.isFile()) {
            throw new WebApplicationException(Response.status(Response.Status.NOT_FOUND).build());
        }

        /*
         * The file name embeds an upload timestamp, so the content of a given
         * URL never changes: the response can be cached aggressively by the
         * browser and intermediate proxies.
         */
        StreamingOutput stream = (output) -> {
            Files.copy(file.toPath(), output);
        };

        return Response.ok(stream)
                .type(contentType(file.getName()))
                .header("Content-Disposition", "inline; filename=\"" + file.getName() + "\"")
                .header("Content-Length", file.length())
                .header("Cache-Control", "public, max-age=31536000, immutable")
                .header("ETag", '"' + Long.toHexString(file.lastModified()) + '-'
                        + Long.toHexString(file.length()) + '"')
                .build();
    }

    @POST
    @Path("{name}")
    @Consumes(MediaType.APPLICATION_OCTET_STREAM)
    public Response upload(@PathParam("name") String name, InputStream input) throws IOException, StorageException {
        permissionsService.checkAdmin(getUserId());
        File file = checkFile(name);

        File directory = getLoginDirectory();
        File temporary = File.createTempFile("upload", null, directory);
        try {
            long size = 0;
            byte[] buffer = new byte[8192];
            int read;
            try (var output = Files.newOutputStream(temporary.toPath())) {
                while ((read = input.read(buffer)) >= 0) {
                    size += read;
                    if (size > MAX_FILE_SIZE) {
                        throw new WebApplicationException(
                                Response.status(Response.Status.REQUEST_ENTITY_TOO_LARGE).build());
                    }
                    output.write(buffer, 0, read);
                }
            }
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } finally {
            temporary.delete();
        }

        return Response.ok(file.getName()).build();
    }

    @DELETE
    @Path("{name}")
    public Response remove(@PathParam("name") String name) throws IOException, StorageException {
        permissionsService.checkAdmin(getUserId());
        File file = checkFile(name);

        if (!file.isFile()) {
            throw new WebApplicationException(Response.status(Response.Status.NOT_FOUND).build());
        }

        Files.delete(file.toPath());
        return Response.noContent().build();
    }
}
