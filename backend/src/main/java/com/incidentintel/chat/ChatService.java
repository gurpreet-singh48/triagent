package com.incidentintel.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class ChatService {

    private static final int SNIPPET_MAX_LENGTH = 300;

    private final ChatClient chatClient;
    private final QuestionAnswerAdvisor questionAnswerAdvisor;
    private final ObjectMapper objectMapper;

    public ChatService(ChatClient.Builder chatClientBuilder, VectorStore vectorStore, ObjectMapper objectMapper) {
        this.chatClient = chatClientBuilder.build();
        this.questionAnswerAdvisor = QuestionAnswerAdvisor.builder(vectorStore).build();
        this.objectMapper = objectMapper;
    }

    public ChatReply chat(String message) {
        ChatClientResponse response = chatClient.prompt()
                .user(message)
                .advisors(questionAnswerAdvisor)
                .call()
                .chatClientResponse();

        String answer = response.chatResponse().getResult().getOutput().getText();
        List<ChatSource> sources = extractSources(response);
        return new ChatReply(answer, sources);
    }

    /**
     * SSE stream of "sources" (once, as soon as retrieval completes) followed
     * by "token" events (one per model delta), ending with a "done" event.
     * Sources arrive first because QuestionAnswerAdvisor's retrieval runs
     * before the model call, so it's already in the first emitted response's
     * context.
     */
    public Flux<ServerSentEvent<String>> streamChat(String message) {
        AtomicBoolean sourcesSent = new AtomicBoolean(false);

        Flux<ServerSentEvent<String>> events = chatClient.prompt()
                .user(message)
                .advisors(questionAnswerAdvisor)
                .stream()
                .chatClientResponse()
                .concatMap(response -> {
                    List<ServerSentEvent<String>> chunk = new java.util.ArrayList<>();
                    if (sourcesSent.compareAndSet(false, true)) {
                        chunk.add(ServerSentEvent.<String>builder(toJson(Map.of("sources", extractSources(response))))
                                .event("sources")
                                .build());
                    }
                    String delta = response.chatResponse().getResult().getOutput().getText();
                    if (delta != null && !delta.isEmpty()) {
                        chunk.add(ServerSentEvent.<String>builder(toJson(Map.of("text", delta)))
                                .event("token")
                                .build());
                    }
                    return Flux.fromIterable(chunk);
                });

        return events.concatWith(Flux.just(ServerSentEvent.<String>builder("{}").event("done").build()));
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("failed to serialize chat SSE payload", e);
        }
    }

    @SuppressWarnings("unchecked")
    private List<ChatSource> extractSources(ChatClientResponse response) {
        List<Document> retrieved = (List<Document>) response.context()
                .getOrDefault(QuestionAnswerAdvisor.RETRIEVED_DOCUMENTS, List.of());
        return retrieved.stream().map(this::toSource).toList();
    }

    private ChatSource toSource(Document document) {
        String docId = (String) document.getMetadata().get("doc_id");
        String title = (String) document.getMetadata().get("title");
        String text = document.getText() != null ? document.getText() : "";
        String snippet = text.length() > SNIPPET_MAX_LENGTH ? text.substring(0, SNIPPET_MAX_LENGTH) + "..." : text;
        return new ChatSource(docId, title, snippet, document.getScore());
    }
}
