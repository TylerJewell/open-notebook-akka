package io.akka.opennotebook.api;

import akka.http.javadsl.model.HttpResponse;
import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.Delete;
import akka.javasdk.annotations.http.Get;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.annotations.http.Post;
import akka.javasdk.annotations.http.Put;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.http.AbstractHttpEndpoint;
import akka.javasdk.http.HttpResponses;
import io.akka.opennotebook.application.NotebookEntity;
import io.akka.opennotebook.application.SourceEntity;
import io.akka.opennotebook.application.SourceIngestionWorkflow;
import io.akka.opennotebook.application.SourcesView;
import io.akka.opennotebook.application.TransformationEntity;
import io.akka.opennotebook.domain.ExtractionRequest;
import io.akka.opennotebook.domain.Insight;
import io.akka.opennotebook.ai.AiClient;
import io.akka.opennotebook.application.DefaultModelsEntity;
import io.akka.opennotebook.domain.DefaultModels;
import io.akka.opennotebook.domain.Source;
import io.akka.opennotebook.domain.Transformation;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The frontend's {@code frontend/src/lib/api/sources.ts} and {@code insights.ts} against {@link
 * SourceEntity} / {@link SourcesView} / {@link SourceIngestionWorkflow} -- same R1-R7, R9-R12,
 * R15 rules as the bare-path {@code SourceEndpoint}, snake_case wire shape, plus the list,
 * status-stream, retry and insight routes the bare-path surface never needed.
 *
 * <p><b>Narrowed, and verified rather than assumed:</b> {@code sourcesApi.create} and {@code
 * .upload} in the source send {@code multipart/form-data}; this endpoint accepts JSON only.
 * Confirmed by decompiling {@code akka-javasdk-3.6.3.jar}'s own {@code RequestContext} and
 * {@code impl/http} classes rather than assuming from a doc read (PIPELINE.md's own warning about
 * that shortcut) -- no multipart type exists in the SDK at all, and {@code RequestContext} exposes
 * headers and query params only, never the raw request entity a hand-rolled multipart parser
 * would need. What is real underneath the original's own multipart route, though, is a
 * <i>second</i> way in: {@code source_data.file_path}, the original's own "backward
 * compatibility" field for a file already placed inside its uploads directory, needing no
 * multipart parsing on either side. {@code type=file} with a JSON {@code file_path} is
 * implemented for real here (see {@link io.akka.opennotebook.domain.LocalFileExtraction}); only
 * the raw-bytes transport itself is unavailable. The vendored frontend's {@code sources.ts} is
 * repointed to send a JSON body instead (a data-layer change RENDERING.md R4 sanctions); a file
 * picked in the browser has nothing to bind to on either side and is dropped client-side rather
 * than silently failing server-side.
 */
@HttpEndpoint("/api/sources")
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.ALL))
public class ApiSourceEndpoint extends AbstractHttpEndpoint {

  public record CreateRequest(
      String type,
      String content,
      String url,
      String file_path,
      String title,
      List<String> notebooks,
      String notebook_id) {}

  public record UpdateRequest(String title) {}

  public record SourceListResponse(
      String id,
      String title,
      Asset asset,
      boolean embedded,
      int embedded_chunks,
      int insights_count,
      Instant created,
      Instant updated,
      String status) {}

  public record Asset(String file_path, String url) {}

  public record SourceDetailResponse(
      String id,
      String title,
      Asset asset,
      boolean embedded,
      int embedded_chunks,
      int insights_count,
      Instant created,
      Instant updated,
      String status,
      String full_text,
      List<String> notebooks) {}

  public record SourceStatusResponse(String status, String message, String command_id) {}

  public record InsightResponse(String id, String insight_type, String content) {}

  public record CreateInsightRequest(String transformation_id, String model_id) {}

  public record InsightCreatedResponse(
      String status, String message, String source_id, String transformation_id, String command_id) {}

  private final ComponentClient componentClient;
  private final AiClient aiClient;

  public ApiSourceEndpoint(ComponentClient componentClient) {
    this.componentClient = componentClient;
    this.aiClient = new AiClient(componentClient);
  }

  @Get("")
  public HttpResponse list() {
    var unauthorized = AuthGuard.check(requestContext());
    if (unauthorized != null) return unauthorized;
    String notebookId = requestContext().queryParams().getString("notebook_id").orElse(null);
    SourcesView.Entries entries =
        (notebookId == null || notebookId.isBlank())
            ? componentClient.forView().method(SourcesView::all).invoke()
            : componentClient.forView().method(SourcesView::byNotebook).invoke(notebookId);
    return HttpResponses.ok(
        entries.items().stream()
            .map(this::toListApi)
            .sorted(java.util.Comparator.comparing(SourceListResponse::updated).reversed())
            .toList());
  }

  @Get("/{sourceId}")
  public HttpResponse get(String sourceId) {
    var unauthorized = AuthGuard.check(requestContext());
    if (unauthorized != null) return unauthorized;
    Source source = fetch(sourceId);
    if (source == null) return HttpResponses.notFound("Source not found");
    return HttpResponses.ok(toDetailApi(source));
  }

  @Post("")
  public HttpResponse create(CreateRequest request) {
    var unauthorized = AuthGuard.check(requestContext());
    if (unauthorized != null) return unauthorized;
    List<String> notebooks =
        request.notebooks() != null
            ? request.notebooks()
            : (request.notebook_id() != null ? List.of(request.notebook_id()) : List.of());

    ExtractionRequest extractionRequest;
    String url = null;
    String filePath = null;
    if ("text".equals(request.type())) {
      if (request.content() == null || request.content().isBlank()) {
        return HttpResponses.badRequest("Content is required for text type");
      }
      extractionRequest = new ExtractionRequest.PlainText(request.content());
    } else if ("link".equals(request.type())) {
      if (request.url() == null || request.url().isBlank()) {
        return HttpResponses.badRequest("URL is required for link type");
      }
      url = request.url();
      extractionRequest = new ExtractionRequest.Url(request.url());
    } else if ("file".equals(request.type()) || "upload".equals(request.type())) {
      // The multipart body itself has nothing to bind to (see class doc); a file_path already
      // placed inside the uploads directory is the original's own "backward compatibility" mode
      // and needs no multipart parsing at all -- LocalFileExtraction enforces the same
      // within-uploads-directory guard the original's router does.
      if (request.file_path() == null || request.file_path().isBlank()) {
        return HttpResponses.badRequest("File upload or file_path is required for upload type");
      }
      filePath = request.file_path();
      extractionRequest = new ExtractionRequest.FilePath(filePath);
    } else {
      return HttpResponses.badRequest("Invalid source type. Must be link, text, or file");
    }

    String sourceId = UUID.randomUUID().toString();
    componentClient
        .forEventSourcedEntity(sourceId)
        .method(SourceEntity::createPlaceholder)
        .invoke(new SourceEntity.CreatePlaceholder(request.title(), url, filePath, notebooks, Instant.now()));
    for (String notebookId : notebooks) {
      componentClient
          .forEventSourcedEntity(notebookId)
          .method(NotebookEntity::linkSource)
          .invoke(new NotebookEntity.SourceLinked(sourceId, Instant.now()));
    }
    componentClient
        .forWorkflow(sourceId)
        .method(SourceIngestionWorkflow::start)
        .invoke(SourceIngestionWorkflow.Start.of(sourceId, extractionRequest));
    return HttpResponses.created(toDetailApi(fetch(sourceId)), "/api/sources/" + sourceId);
  }

  /** {@code api/routers/sources.py}'s own doc calls this "legacy... for backward compatibility" --
   * a JSON-body alias for {@link #create}, which is already JSON-only end to end here. */
  @Post("/json")
  public HttpResponse createJson(CreateRequest request) {
    return create(request);
  }

  /** The original's {@code GET /sources/{id}/download}: the raw bytes of a {@code file}-type
   * source's own file, read from the same uploads directory {@link
   * io.akka.opennotebook.domain.LocalFileExtraction} enforces on ingestion. Not implemented:
   * the original's {@code HEAD} variant -- this SDK version has no {@code @Head} route-binding
   * annotation to declare it with (confirmed against {@code akka-javasdk-3.6.3.jar}'s {@code
   * annotations.http} package, which declares Get/Post/Put/Patch/Delete/WebSocket only). */
  @Get("/{sourceId}/download")
  public HttpResponse download(String sourceId) {
    var unauthorized = AuthGuard.check(requestContext());
    if (unauthorized != null) return unauthorized;
    Source source = fetch(sourceId);
    if (source == null) return HttpResponses.notFound("Source not found");
    if (source.filePath() == null || source.filePath().isBlank()) {
      return HttpResponses.notFound("Source has no file to download");
    }
    java.nio.file.Path uploadsRoot =
        java.nio.file.Path.of(io.akka.opennotebook.domain.LocalFileExtraction.uploadsRoot()).toAbsolutePath().normalize();
    java.nio.file.Path resolved = java.nio.file.Path.of(source.filePath()).toAbsolutePath().normalize();
    if (!resolved.startsWith(uploadsRoot)) {
      return HttpResponses.of(akka.http.javadsl.model.StatusCodes.FORBIDDEN, akka.http.javadsl.model.ContentTypes.TEXT_PLAIN_UTF8, "Access to file denied".getBytes());
    }
    if (!java.nio.file.Files.isRegularFile(resolved)) {
      return HttpResponses.notFound("File not found on server");
    }
    try {
      byte[] bytes = java.nio.file.Files.readAllBytes(resolved);
      return HttpResponses.of(akka.http.javadsl.model.StatusCodes.OK, akka.http.javadsl.model.ContentTypes.APPLICATION_OCTET_STREAM, bytes);
    } catch (java.io.IOException e) {
      return HttpResponses.internalServerError("Failed to download source file");
    }
  }

  @Put("/{sourceId}")
  public HttpResponse update(String sourceId, UpdateRequest request) {
    var unauthorized = AuthGuard.check(requestContext());
    if (unauthorized != null) return unauthorized;
    try {
      componentClient
          .forEventSourcedEntity(sourceId)
          .method(SourceEntity::updateTitle)
          .invoke(new SourceEntity.UpdateTitle(request.title(), Instant.now()));
    } catch (Exception e) {
      return HttpResponses.badRequest(e.getMessage());
    }
    return HttpResponses.ok(toListApi(fetch(sourceId)));
  }

  @Get("/{sourceId}/status")
  public HttpResponse status(String sourceId) {
    var unauthorized = AuthGuard.check(requestContext());
    if (unauthorized != null) return unauthorized;
    Source source = fetch(sourceId);
    if (source == null) return HttpResponses.notFound("Source not found");
    return HttpResponses.ok(new SourceStatusResponse(source.status().name().toLowerCase(), statusMessage(source), null));
  }

  /**
   * RENDERING.md R1: replaces {@code useSourceStatus}'s 2s {@code refetchInterval} poll
   * ({@code frontend/src/lib/hooks/use-sources.ts:234-259}) with a subscription. First event is
   * the current status (R1.4: no second round trip needed to see current state); subsequent
   * events arrive as {@link SourceEntity} persists them, via its {@code NotificationPublisher}.
   * {@code lastSeenSseEventId} drives R1.3 reconnect -- the SSE id is the source's own
   * {@code updatedAt} instant, so a client reconnecting after a gap resumes rather than
   * replaying from the start (subject to the notification stream's own at-least-once, no-replay
   * contract: a reconnect after the publisher itself restarted re-fetches current state via the
   * same {@code Source.single(current)} priming step, not a gap-filling replay).
   */
  @Get("/{sourceId}/status/stream")
  public HttpResponse statusStream(String sourceId) {
    var unauthorized = AuthGuard.check(requestContext());
    if (unauthorized != null) return unauthorized;
    Source current = fetch(sourceId);
    if (current == null) return HttpResponses.notFound("Source not found");

    var notifications =
        componentClient.forEventSourcedEntity(sourceId).notificationStream(SourceEntity::updates).source();
    var stream =
        akka.stream.javadsl.Source.single(current)
            .concat(notifications.map(event -> fetch(sourceId)).filter(java.util.Objects::nonNull))
            .map(s -> new SourceStatusResponse(s.status().name().toLowerCase(), statusMessage(s), null));
    return HttpResponses.serverSentEvents(stream, s -> Instant.now().toString());
  }

  @Post("/{sourceId}/retry")
  public HttpResponse retry(String sourceId) {
    var unauthorized = AuthGuard.check(requestContext());
    if (unauthorized != null) return unauthorized;
    Source source = fetch(sourceId);
    if (source == null) return HttpResponses.notFound("Source not found");
    if (source.status() != io.akka.opennotebook.domain.SourceStatus.FAILED) {
      return HttpResponses.badRequest("Only a failed source can be retried");
    }
    // D-2 (SPEC-001): a failed text-type source has nothing to retry from -- the submitted text
    // is never stored anywhere once extraction fails, on either side of this port. A link-type
    // source retries for real: its url survived the failure (R4) and is re-fetched.
    if (source.url() == null || source.url().isBlank()) {
      return HttpResponses.badRequest("This source has no retryable content (D-2: unreachable on both sides for a failed text source)");
    }
    componentClient
        .forWorkflow(sourceId)
        .method(SourceIngestionWorkflow::start)
        .invoke(SourceIngestionWorkflow.Start.of(sourceId, new ExtractionRequest.Url(source.url())));
    return HttpResponses.ok(toDetailApi(fetch(sourceId)));
  }

  @Delete("/{sourceId}")
  public HttpResponse delete(String sourceId) {
    var unauthorized = AuthGuard.check(requestContext());
    if (unauthorized != null) return unauthorized;
    Source source = fetch(sourceId);
    if (source == null) return HttpResponses.notFound("Source not found");
    Instant now = Instant.now();
    for (String notebookId : source.notebookIds()) {
      componentClient
          .forEventSourcedEntity(notebookId)
          .method(NotebookEntity::unlinkSource)
          .invoke(new NotebookEntity.SourceUnlinked(sourceId, now));
    }
    componentClient.forEventSourcedEntity(sourceId).method(SourceEntity::delete).invoke(new SourceEntity.SourceDeleted(now));
    return HttpResponses.ok();
  }

  @Get("/{sourceId}/insights")
  public HttpResponse listInsights(String sourceId) {
    var unauthorized = AuthGuard.check(requestContext());
    if (unauthorized != null) return unauthorized;
    Source source = fetch(sourceId);
    if (source == null) return HttpResponses.notFound("Source not found");
    return HttpResponses.ok(source.insights().stream().map(insight -> toInsightApi(sourceId, insight)).toList());
  }

  /**
   * Synchronous, unlike the source's job-queue-backed version: this generates the insight inline
   * and replies with {@code status: "completed"} and no {@code command_id}, so the frontend's
   * poll-until-done helper ({@code insightsApi.waitForCommand}) has nothing to poll -- the work
   * is already done by the time this call returns.
   */
  @Post("/{sourceId}/insights")
  public HttpResponse createInsight(String sourceId, CreateInsightRequest request) {
    var unauthorized = AuthGuard.check(requestContext());
    if (unauthorized != null) return unauthorized;
    Source source = fetch(sourceId);
    if (source == null) return HttpResponses.notFound("Source not found");
    if (source.fullText() == null || source.fullText().isBlank()) {
      return HttpResponses.badRequest("Source has no extracted text to transform");
    }
    Transformation transformation;
    try {
      transformation =
          componentClient.forKeyValueEntity(request.transformation_id()).method(TransformationEntity::get).invoke();
    } catch (Exception e) {
      return HttpResponses.badRequest("Transformation not found");
    }
    String modelId = request.model_id() != null ? request.model_id() : transformation.modelId();
    if (modelId == null) {
      DefaultModels defaults =
          componentClient.forKeyValueEntity(DefaultModelsEntity.ID).method(DefaultModelsEntity::get).invoke();
      modelId = defaults.defaultTransformationModel();
    }
    String systemPrompt =
        transformation.prompt()
            + "\n\n# MATH FORMATTING\n\nWhen showing math, write display math as $$...$$ and "
            + "inline math as $...$ so formulas render properly.\n\n# INPUT";
    String content;
    try {
      content = aiClient.chatComplete(modelId, systemPrompt, List.of(new AiClient.ChatMessage("user", source.fullText())));
    } catch (Exception e) {
      return HttpResponses.badRequest(e.getMessage());
    }
    componentClient
        .forEventSourcedEntity(sourceId)
        .method(SourceEntity::addInsight)
        .invoke(new SourceEntity.AddInsight(transformation.title(), content, Instant.now()));
    return HttpResponses.ok(
        new InsightCreatedResponse("completed", "Insight generated", sourceId, request.transformation_id(), null));
  }

  private Source fetch(String sourceId) {
    try {
      return componentClient.forEventSourcedEntity(sourceId).method(SourceEntity::get).invoke();
    } catch (Exception e) {
      return null;
    }
  }

  private static String statusMessage(Source source) {
    return switch (source.status()) {
      case NEW -> "Queued";
      case RUNNING -> "Processing";
      case COMPLETED -> "Completed";
      case FAILED -> source.errorMessage() != null ? source.errorMessage() : "Failed";
    };
  }

  private SourceListResponse toListApi(Source s) {
    return new SourceListResponse(
        s.sourceId(), s.title(), new Asset(s.filePath(), s.url()), false, 0, s.insights().size(),
        s.createdAt(), s.updatedAt(), s.status().name().toLowerCase());
  }

  private SourceListResponse toListApi(SourcesView.Entry e) {
    return new SourceListResponse(
        e.sourceId(), e.title(), new Asset(e.filePath().orElse(null), e.url().orElse(null)), false, 0, e.insightsCount(),
        e.createdAt(), e.updatedAt(), e.status().toLowerCase());
  }

  private SourceDetailResponse toDetailApi(Source s) {
    return new SourceDetailResponse(
        s.sourceId(), s.title(), new Asset(s.filePath(), s.url()), false, 0, s.insights().size(),
        s.createdAt(), s.updatedAt(), s.status().name().toLowerCase(), s.fullText(), List.copyOf(s.notebookIds()));
  }

  private InsightResponse toInsightApi(String sourceId, Insight insight) {
    // The composite id, not the index: two callers deleting different insights out of order
    // would otherwise each address the other's insight once the list shifts underneath them.
    return new InsightResponse(sourceId + ":" + insight.id(), insight.insightType(), insight.content());
  }
}
