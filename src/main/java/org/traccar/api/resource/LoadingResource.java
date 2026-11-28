/*
 * Copyright 2026 - Traccar fork customization (login loading screen files)
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
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Storage for the login "loading screen" animations (GIF / MP4) selected by
 * the administrator in the web client (Settings / Server).
 *
 * The web client stores the file name in the server attributes
 *   loading.windows.file / loading.mobile.file
 * and the playback duration (milliseconds) in
 *   loading.windows.duration / loading.mobile.duration
 * (default 4500 = 4.5 seconds). Those attributes are served to the login page
 * through the standard public "api/server" endpoint.
 *
 * Endpoints:
 *   POST   /api/server/loading/{name}   upload (admin only)
 *   GET    /api/server/loading          list (admin only, Cache-Control: no-store)
 *   GET    /api/server/loading/status   diagnostics (admin only): returns the
 *                                       exact storage directory the server is
 *                                       using, whether it exists and which
 *                                       files are inside. This makes cases like
 *                                       "redeploy / migration wiped data/loading"
 *                                       or a wrong loading.path instantly visible
 *                                       from the settings page.
 *   GET    /api/server/loading/manifest/{name}
 *                                       existence probe (public, @PermitAll):
 *                                       always answers 200 JSON {name, exists}
 *                                       with Cache-Control: no-store. The login
 *                                       page asks it BEFORE fetching the binary,
 *                                       so a missing file silently falls back to
 *                                       the bundled animation instead of logging
 *                                       a 404 and a 405 in the browser console.
 *   GET    /api/server/loading/{name}   download (public, @PermitAll) so the
 *                                       login page can display the animation
 *                                       before authentication
 *   DELETE /api/server/loading/{name}   remove (admin only)
 *
 * Files are stored in the directory configured with "loading.path" in
 * traccar.xml (default: ./data/loading):
 *   <entry key='loading.path'>./data/loading</entry>
 *
 * Downloaded files carry long-lived caching headers; every upload gets a new
 * timestamped name on the client side, so replacing a file automatically
 * busts the browser cache (requirement #4 / #5).
 *
 * Caching policy notes (404 fix): the file LIST and every NOT_FOUND response
 * are sent with "Cache-Control: no-store". A cacheable list allowed CDN/browser
 * caches to keep showing files that no longer exist on the server, and a
 * cacheable 404 could stick on a CDN edge even after the file was restored.
 * File downloads keep their immutable long-lived caching on purpose.
 */
@Path("server/loading")
@Produces(MediaType.APPLICATION_JSON)
public class LoadingResource extends BaseResource {

    private static final String DEFAULT_LOADING_PATH = "./data/loading";

    private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");

    private static final long MAX_FILE_SIZE = 25 * 1024 * 1024; // 25 MB

    @Inject
    private Config config;

    private File getLoadingDirectory() throws IOException {
        String path = config.getString("loading.path");
        if (path == null || path.isEmpty()) {
            path = DEFAULT_LOADING_PATH;
        }
        File directory = new File(path);
        Files.createDirectories(directory.toPath());
        return directory.getCanonicalFile();
    }

    /* 404 that intermediate caches (CDN/browser) must never store */
    private static WebApplicationException notFound() {
        return new WebApplicationException(Response.status(Response.Status.NOT_FOUND)
                .header("Cache-Control", "no-store").build());
    }

    /* name pattern + extension validation without throwing (used by manifest) */
    private static boolean isSafeMediaName(String name) {
        if (name == null) {
            return false;
        }
        String lower = name.toLowerCase();
        return SAFE_NAME.matcher(name).matches()
                && (lower.endsWith(".gif") || lower.endsWith(".mp4"));
    }

    private void checkName(String name) throws IOException {
        if (!isSafeMediaName(name)) {
            throw notFound();
        }
        File directory = getLoadingDirectory();
        File file = new File(directory, name).getCanonicalFile();
        if (!file.getPath().startsWith(directory.getPath() + File.separator)) {
            throw notFound();
        }
    }

    @GET
    public Response get() throws IOException, StorageException {
        permissionsService.checkAdmin(getUserId());

        List<Map<String, Object>> result = new java.util.ArrayList<>();
        File[] files = getLoadingDirectory().listFiles(
                (dir, name) -> name.toLowerCase().endsWith(".gif") || name.toLowerCase().endsWith(".mp4"));
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
        /*
         * The list changes with every upload/delete, so it must never be cached
         * by the browser or a CDN ("SMART" edge policies included); a cached
         * list shows files that are no longer on the server and makes the 404
         * of the preview impossible to explain for the administrator.
         */
        return Response.ok(result).header("Cache-Control", "no-store").build();
    }

    @GET
    @Path("status")
    public Map<String, Object> status() throws IOException, StorageException {
        permissionsService.checkAdmin(getUserId());

        File directory = getLoadingDirectory();
        String[] names = directory.list((dir, name) ->
                name.toLowerCase().endsWith(".gif") || name.toLowerCase().endsWith(".mp4"));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("path", directory.getPath());
        result.put("exists", directory.isDirectory());
        result.put("fileCount", names == null ? 0 : names.length);
        result.put("files", names == null ? List.of() : Arrays.asList(names));
        return result;
    }

    /*
     * Public existence probe for the pre-login page. The login page used to
     * fetch the binary directly; when the configured file was missing from the
     * storage directory (e.g. data/loading lost after a redeploy) every visit
     * logged one 404 from the primary URL and one 405 from the legacy fallback
     * URL in the browser console. With this manifest the client learns
     * exists=false from a lightweight 200 JSON answer and skips the binary
     * fetch entirely, so the console stays clean. The answer carries
     * "Cache-Control: no-store" so neither browser caches nor CDN negative
     * flow caches (WCDN "SMART" policy) can pin stale existence information.
     */
    @GET
    @Path("manifest/{name}")
    @PermitAll
    public Response manifest(@PathParam("name") String name) throws IOException, StorageException {
        boolean exists = false;
        if (isSafeMediaName(name)) {
            File directory = getLoadingDirectory();
            File file = new File(directory, name).getCanonicalFile();
            exists = file.getPath().startsWith(directory.getPath() + File.separator)
                    && file.isFile();
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("name", name == null ? "" : name);
        result.put("exists", exists);
        return Response.ok(result)
                .header("Cache-Control", "no-store")
                .build();
    }

    @GET
    @Path("{name}")
    @PermitAll
    public Response download(@PathParam("name") String name) throws IOException {
        checkName(name);

        File directory = getLoadingDirectory();
        File file = new File(directory, name).getCanonicalFile();
        if (!file.isFile()) {
            /* file missing on the server (e.g. lost data/loading after redeploy);
               keep it out of CDN/browser negative caches */
            throw notFound();
        }

        String contentType = name.toLowerCase().endsWith(".mp4")
                ? "video/mp4" : "image/gif";

        /*
         * The file name embeds an upload timestamp, so the content of a given
         * URL never changes: the response can be cached aggressively by the
         * browser and intermediate proxies (requirement #4 / #5).
         */
        StreamingOutput stream = (output) -> {
            Files.copy(file.toPath(), output);
        };

        return Response.ok(stream)
                .type(contentType)
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
        checkName(name);

        File directory = getLoadingDirectory();
        File file = new File(directory, name).getCanonicalFile();
        if (!file.getPath().startsWith(directory.getPath() + File.separator)) {
            throw notFound();
        }

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
        checkName(name);

        File directory = getLoadingDirectory();
        File file = new File(directory, name).getCanonicalFile();
        if (!file.getPath().startsWith(directory.getPath() + File.separator) || !file.isFile()) {
            throw notFound();
        }

        Files.delete(file.toPath());
        return Response.noContent().build();
    }
}
