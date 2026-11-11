package com.stonewu.agenteam.service.meeting;

import com.fasterxml.jackson.databind.JsonNode;
import com.stonewu.agenteam.model.plugin.entity.BuiltinExecutionContext;
import com.stonewu.agenteam.service.plugin.PlatformToolHandler;
import org.springframework.stereotype.Service;

import java.util.Set;

/**
 * 会议管理内置插件的业务工具处理器。只登记会议任务发布这一个写工具，
 * 全部身份与授权由宿主执行上下文和发布服务重新校验，不接收模型传入身份。
 */
@Service
public class MeetingTaskPublishToolHandler implements PlatformToolHandler {
    private final MeetingTaskPublicationService publication;

    public MeetingTaskPublishToolHandler(MeetingTaskPublicationService publication) {
        this.publication = publication;
    }

    @Override
    public Set<String> toolNames() {
        return Set.of("meeting_task_publish");
    }

    @Override
    public JsonNode call(BuiltinExecutionContext context, String name, JsonNode arguments) {
        return publication.publish(context, arguments);
    }
}
