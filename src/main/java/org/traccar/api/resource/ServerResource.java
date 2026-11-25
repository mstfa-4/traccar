/*
 * Copyright 2015 - 2026 Anton Tananaev (anton@traccar.org)
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
import org.traccar.config.Keys;
import org.traccar.database.OpenIdProvider;
import org.traccar.geocoder.Geocoder;
import org.traccar.helper.Log;
import org.traccar.helper.LogAction;
import org.traccar.helper.model.UserUtil;
import org.traccar.mail.MailManager;
import org.traccar.model.ObjectOperation;
import org.traccar.model.Server;
import org.traccar.model.User;
import org.traccar.session.cache.CacheManager;
import org.traccar.sms.SmsManager;
import org.traccar.storage.StorageException;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Request;

import jakarta.annotation.Nullable;
import jakarta.annotation.security.PermitAll;
import jakarta.inject.Inject;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.StreamingOutput;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.stream.Stream;

@Path("server")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ServerResource extends BaseResource {

    public static final String LOADING_WINDOWS_PREFIX = "loading-windows";

    public static final String LOADING_MOBILE_PREFIX = "loading-mobile";

    public static final String LOADING_DURATION_ATTRIBUTE = "loadingDuration";

    public static final long DEFAULT_LOADING_DURATION = 4500;

    // Media files (loading screen animations and images) can be downloaded
    // without authentication, because the login page shows the loading
    // animation before the user is authenticated. Any other file type still
    // requires administrator permissions.
    private static final Set<String> PUBLIC_MEDIA_EXTENSIONS = Set.of(
            "gif", "mp4", "webm", "png", "jpg", "jpeg", "webp", "svg");

    @Inject
    private Config config;

    @Inject
    private CacheManager cacheManager;

    @Inject
    private MailManager mailManager;

    @Inject
    @Nullable
    private SmsManager smsManager;

    @Inject
    @Nullable
    private OpenIdProvider openIdProvider;

    @Inject
    @Nullable
    private Geocoder geocoder;

    @Inject
    private LogAction actionLogger;

    @Context
    private HttpServletRequest request;

    @PermitAll
    @GET
    public Server get() throws StorageException {
        Server server = storage.getObject(Server.class, new Request(new Columns.All()));
        server.setEmailEnabled(mailManager.getEmailEnabled());
        server.setTextEnabled(smsManager != null);
        server.setGeocoderEnabled(geocoder != null);
        server.setOpenIdEnabled(openIdProvider != null);
        server.setOpenIdForce(openIdProvider != null && openIdProvider.getForce());
        User user = permissionsService.getUser(getUserId());
        if (user != null) {
            if (user.getAdministrator()) {
                server.setStorageSpace(Log.getStorageSpace());
            }
        } else {
            server.setNewServer(UserUtil.isEmpty(storage));
        }
        return server;
    }

    @PUT
    public Response update(Server server) throws Exception {
        permissionsService.checkAdmin(getUserId());
        storage.updateObject(server, new Request(
                new Columns.Exclude("id"),
                new Condition.Equals("id", server.getId())));
        cacheManager.invalidateObject(true, Server.class, server.getId(), ObjectOperation.UPDATE);
        actionLogger.edit(request, getUserId(), server);
        return Response.ok(server).build();
    }

    @Path("geocode")
    @GET
    public String geocode(@QueryParam("latitude") double latitude, @QueryParam("longitude") double longitude) {
        if (geocoder != null) {
            return geocoder.getAddress(latitude, longitude, null);
        } else {
            throw new RuntimeException("Reverse geocoding is not enabled");
        }
    }

    @Path("timezones")
    @GET
    public Collection<String> timezones() {
        return List.of(TimeZone.getAvailableIDs());
    }

    private Path getFileRoot() {
        return Paths.get(config.getString(Keys.WEB_OVERRIDE, config.getString(Keys.WEB_PATH))).normalize();
    }

    private Path resolvePath(Path root, String relative) {
        Path resolved = root.resolve(relative).normalize();
        return resolved.startsWith(root) ? resolved : null;
    }

    private String getExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
    }

    private String getContentType(String name) {
        return switch (getExtension(name)) {
            case "gif" -> "image/gif";
            case "mp4" -> "video/mp4";
            case "webm" -> "video/webm";
            case "png" -> "image/png";
            case "jpg", "jpeg" -> "image/jpeg";
            case "webp" -> "image/webp";
            case "svg" -> "image/svg+xml";
            default -> "application/octet-stream";
        };
    }

    /** Returns the newest regular file in the root whose name starts with the given prefix. */
    private Path findNewestFile(Path root, String prefix) throws IOException {
        if (!Files.isDirectory(root)) {
            return null;
        }
        try (Stream<Path> files = Files.list(root)) {
            return files.filter(Files::isRegularFile)
                    .filter((path) -> path.getFileName().toString().startsWith(prefix))
                    .max(Comparator.comparingLong((path) -> path.toFile().lastModified()))
                    .orElse(null);
        }
    }

    /** Streams a file with content type and cache headers. */
    private Response serveFile(Path file) throws IOException {
        String name = file.getFileName().toString();
        String etag = '"' + Long.toHexString(file.toFile().lastModified())
                + '-' + Long.toHexString(file.toFile().length()) + '"';
        StreamingOutput content = (output) -> Files.copy(file, output);
        return Response.ok(content, getContentType(name))
                .header("Content-Disposition", "inline; filename=\"" + name + "\"")
                .header("Content-Length", file.toFile().length())
                // File names include an upload timestamp, so the URL changes on
                // every new upload and the browser cache can be long-lived.
                .header("Cache-Control", "public, max-age=604800")
                .header("ETag", etag)
                .build();
    }

    @Path("file")
    @GET
    public List<String> getFiles() throws IOException, StorageException {
        permissionsService.checkAdmin(getUserId());
        var root = getFileRoot();
        try (var files = Files.exists(root) ? Files.list(root) : Stream.<Path>empty()) {
            return files.filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString()).sorted().toList();
        }
    }

    /**
     * Downloads an uploaded file. Media files are public (the login page
     * loading animation is displayed before authentication), all other files
     * require administrator permissions.
     */
    @PermitAll
    @Path("file/{path}")
    @GET
    public Response downloadFile(@PathParam("path") String path) throws IOException, StorageException {
        var root = getFileRoot();
        var file = resolvePath(root, path);
        if (file == null || !Files.isRegularFile(file)) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        if (!PUBLIC_MEDIA_EXTENSIONS.contains(getExtension(file.getFileName().toString()))) {
            long userId = getUserId();
            if (userId <= 0) {
                throw new WebApplicationException(
                        Response.status(Response.Status.UNAUTHORIZED).build());
            }
            permissionsService.checkAdmin(userId);
        }
        return serveFile(file);
    }

    /** Returns the current loading screen configuration for the login page. */
    @PermitAll
    @Path("loading")
    @GET
    public Map<String, Object> getLoadingInfo() throws IOException, StorageException {
        var root = getFileRoot();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("windows", loadingFileInfo(root, LOADING_WINDOWS_PREFIX));
        result.put("mobile", loadingFileInfo(root, LOADING_MOBILE_PREFIX));
        result.put("duration", getLoadingDuration());
        return result;
    }

    /**
     * Downloads the loading screen animation by name. The exact file name is
     * tried first (for example "loading-windows-1795598803860.gif"), then the
     * newest file with the same prefix (for example "loading-windows" or
     * "loading-windows.gif"), so the login page can use stable URLs.
     */
    @PermitAll
    @Path("loading/{name}")
    @GET
    public Response downloadLoadingFile(@PathParam("name") String name) throws IOException, StorageException {
        var root = getFileRoot();
        Path file = null;
        Path exact = resolvePath(root, name);
        if (exact != null && Files.isRegularFile(exact)) {
            file = exact;
        } else {
            String prefix = name.toLowerCase(Locale.ROOT);
            int dot = prefix.lastIndexOf('.');
            if (dot > 0) {
                prefix = prefix.substring(0, dot);
            }
            if (prefix.startsWith(LOADING_WINDOWS_PREFIX)) {
                prefix = LOADING_WINDOWS_PREFIX;
            } else if (prefix.startsWith(LOADING_MOBILE_PREFIX)) {
                prefix = LOADING_MOBILE_PREFIX;
            }
            if (prefix.equals(LOADING_WINDOWS_PREFIX) || prefix.equals(LOADING_MOBILE_PREFIX)) {
                file = findNewestFile(root, prefix);
            }
        }
        if (file == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        return serveFile(file);
    }

    private Map<String, Object> loadingFileInfo(Path root, String prefix) throws IOException {
        Path file = findNewestFile(root, prefix);
        if (file == null) {
            return null;
        }
        String name = file.getFileName().toString();
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("name", name);
        info.put("url", "api/server/file/" + name);
        return info;
    }

    private long getLoadingDuration() throws StorageException {
        Server server = storage.getObject(Server.class, new Request(new Columns.All()));
        if (server != null && server.getAttributes() != null) {
            Object value = server.getAttributes().get(LOADING_DURATION_ATTRIBUTE);
            if (value instanceof Number number) {
                long duration = number.longValue();
                if (duration > 0) {
                    return duration;
                }
            }
        }
        return DEFAULT_LOADING_DURATION;
    }

    @Path("file/{path}")
    @POST
    @Consumes("*/*")
    public Response uploadFile(@PathParam("path") String path, File inputFile) throws IOException, StorageException {
        permissionsService.checkAdmin(getUserId());
        var rootPath = getFileRoot();
        var outputPath = resolvePath(rootPath, path);
        if (outputPath == null) {
            return Response.status(Response.Status.BAD_REQUEST).build();
        }

        var directoryPath = outputPath.getParent();
        if (directoryPath != null) {
            Files.createDirectories(directoryPath);
        }

        try (var input = new FileInputStream(inputFile); var output = new FileOutputStream(outputPath.toFile())) {
            input.transferTo(output);
        }
        return Response.ok().build();
    }

    @Path("file/{path}")
    @DELETE
    public Response deleteFile(@PathParam("path") String path) throws IOException, StorageException {
        permissionsService.checkAdmin(getUserId());
        var root = getFileRoot();
        var file = resolvePath(root, path);
        if (file == null || !Files.isRegularFile(file)) {
            return Response.status(Response.Status.BAD_REQUEST).build();
        }
        Files.delete(file);
        return Response.noContent().build();
    }

    @Path("gc")
    @GET
    public Response gc() throws StorageException {
        permissionsService.checkAdmin(getUserId());
        System.gc();
        return Response.ok().build();
    }

    @Path("cache")
    @GET
    public String cache() throws StorageException {
        permissionsService.checkAdmin(getUserId());
        return cacheManager.toString();
    }

    @Path("reboot")
    @POST
    public void reboot() throws StorageException {
        permissionsService.checkAdmin(getUserId());
        System.exit(130);
    }

}
