/*
 * Copyright 2026 - Traccar fork customization (backup list and download API)
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

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.StreamingOutput;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Admin-only REST API for listing and downloading server backup archives
 * produced by traccar-backup.sh (default location: /var/backups/traccar).
 *
 * The directory can be overridden with the "backup.path" config entry
 * in traccar.xml:
 *   <entry key='backup.path'>/var/backups/traccar</entry>
 */
@Path("backups")
@Produces(MediaType.APPLICATION_JSON)
public class BackupResource extends BaseResource {

    private static final String DEFAULT_BACKUP_PATH = "/var/backups/traccar";

    private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");

    @Inject
    private Config config;

    private File getBackupDirectory() throws IOException {
        String path = config.getString("backup.path");
        if (path == null || path.isEmpty()) {
            path = DEFAULT_BACKUP_PATH;
        }
        File directory = new File(path);
        Files.createDirectories(directory.toPath());
        return directory.getCanonicalFile();
    }

    @GET
    public List<Map<String, Object>> get() throws StorageException, IOException {
        permissionsService.checkAdmin(getUserId());

        List<Map<String, Object>> result = new ArrayList<>();
        File[] files = getBackupDirectory().listFiles((dir, name) -> name.endsWith(".tar.gz"));
        if (files != null) {
            Arrays.sort(files, Comparator.comparingLong(File::lastModified).reversed());
            for (File file : files) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("name", file.getName());
                item.put("size", file.length());
                item.put("date", new Date(file.lastModified()));
                result.add(item);
            }
        }
        return result;
    }

    @GET
    @Path("{name}")
    @Produces(MediaType.APPLICATION_OCTET_STREAM)
    public Response download(@PathParam("name") String name) throws StorageException, IOException {
        permissionsService.checkAdmin(getUserId());

        if (name == null || !SAFE_NAME.matcher(name).matches() || !name.endsWith(".tar.gz")) {
            throw new WebApplicationException(Response.status(Response.Status.NOT_FOUND).build());
        }

        File directory = getBackupDirectory();
        File file = new File(directory, name).getCanonicalFile();
        if (!file.getPath().startsWith(directory.getPath() + File.separator) || !file.isFile()) {
            throw new WebApplicationException(Response.status(Response.Status.NOT_FOUND).build());
        }

        StreamingOutput stream = (output) -> {
            Files.copy(file.toPath(), output);
        };

        return Response.ok(stream)
                .header("Content-Disposition", "attachment; filename=\"" + file.getName() + "\"")
                .header("Content-Length", file.length())
                .build();
    }
}
