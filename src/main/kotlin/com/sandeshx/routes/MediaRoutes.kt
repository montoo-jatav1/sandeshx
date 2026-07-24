package com.sandeshx.routes

import com.sandeshx.models.MediaFiles
import com.sandeshx.models.MediaUploadResponse
import com.sandeshx.models.ErrorResponse
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.request.*
import io.ktor.server.routing.*
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.Instant
import java.util.Base64

private const val MAX_UPLOAD_BYTES = 8 * 1024 * 1024 // 8MB

/** Authenticated upload — accepts a multipart image, stores it in Postgres, and
 *  returns a path the client resolves against its own base URL (avoids baking a
 *  hostname into the DB and keeps this working behind any reverse proxy). */
fun Route.mediaRoutes() {
    authenticate("auth-jwt") {
        route("/api/media") {
            post("/upload") {
                val userId = call.currentUserId()
                var bytes: ByteArray? = null
                var contentType = "image/jpeg"

                val multipart = call.receiveMultipart()
                multipart.forEachPart { part ->
                    if (part is PartData.FileItem) {
                        contentType = part.contentType?.toString() ?: contentType
                        @Suppress("DEPRECATION")
                        bytes = part.streamProvider().use { it.readBytes() }
                    }
                    part.dispose()
                }

                val fileBytes = bytes
                if (fileBytes == null || fileBytes.isEmpty()) {
                    return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("No image received"))
                }
                if (fileBytes.size > MAX_UPLOAD_BYTES) {
                    return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Image too large (max 8MB)"))
                }

                val id = transaction {
                    MediaFiles.insertAndGetId {
                        it[MediaFiles.ownerId] = userId
                        it[MediaFiles.contentType] = contentType
                        it[MediaFiles.dataBase64] = Base64.getEncoder().encodeToString(fileBytes)
                        it[MediaFiles.createdAt] = Instant.now()
                    }
                }.value

                call.respond(MediaUploadResponse(id, "/api/media/$id"))
            }
        }
    }
}

/** Public (unauthenticated) fetch by id — ids are opaque and unguessable enough for
 *  chat images/avatars in this app's threat model, and keeping it unauthenticated
 *  means the Android image loader (Coil) can load it with a plain URL. */
fun Route.mediaServingRoute() {
    get("/api/media/{id}") {
        val id = call.parameters["id"]?.toLongOrNull()
            ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid media id"))
        val row = transaction { MediaFiles.selectAll().where { MediaFiles.id eq id }.singleOrNull() }
            ?: return@get call.respond(HttpStatusCode.NotFound, ErrorResponse("Not found"))
        val bytes = Base64.getDecoder().decode(row[MediaFiles.dataBase64])
        call.respondBytes(bytes, ContentType.parse(row[MediaFiles.contentType]))
    }
}
