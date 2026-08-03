package com.incidentintel.chat;

import java.util.List;

public record ChatReply(String answer, List<ChatSource> sources) {
}
