package com.creactiviti.piper.core;


import java.util.Collections;
import java.util.Date;
import java.util.List;

import org.springframework.util.Assert;

import com.creactiviti.piper.core.context.Context;
import com.creactiviti.piper.core.context.ContextRepository;
import com.creactiviti.piper.core.context.MapContext;
import com.creactiviti.piper.core.task.SimpleTaskExecution;
import com.creactiviti.piper.core.task.SpelTaskEvaluator;
import com.creactiviti.piper.core.task.TaskDispatcher;
import com.creactiviti.piper.core.task.TaskEvaluator;
import com.creactiviti.piper.core.task.TaskExecution;
import com.creactiviti.piper.core.task.TaskExecutionRepository;
import com.creactiviti.piper.core.task.TaskStatus;
import com.creactiviti.piper.core.uuid.UUIDGenerator;

/**
 * @author Arik Cohen
 * @since Jun 3, 2017
 */
public class SwitchTaskCompletionHandler implements TaskCompletionHandler {

  private final TaskExecutionRepository taskExecutionRepo;
  private final TaskCompletionHandler taskCompletionHandler;
  private final TaskDispatcher taskDispatcher;
  private final ContextRepository contextRepository;
  private final TaskEvaluator taskEvaluator = new SpelTaskEvaluator();

  public SwitchTaskCompletionHandler(TaskExecutionRepository aTaskExecutionRepo, TaskCompletionHandler aTaskCompletionHandler, TaskDispatcher aTaskDispatcher, ContextRepository aContextRepository) {
    taskExecutionRepo = aTaskExecutionRepo;
    taskCompletionHandler = aTaskCompletionHandler;
    taskDispatcher = aTaskDispatcher;
    contextRepository = aContextRepository;
  }

  @Override
  public void handle (TaskExecution aTaskExecution) {
    SimpleTaskExecution mtask = SimpleTaskExecution.createForUpdate(aTaskExecution);
    mtask.setStatus(TaskStatus.COMPLETED);
    taskExecutionRepo.merge(mtask);

    SimpleTaskExecution switchTask = SimpleTaskExecution.createForUpdate(taskExecutionRepo.findOne(aTaskExecution.getParentId()));
    if(aTaskExecution.getOutput() != null && aTaskExecution.getName() != null) {
      Context context = contextRepository.peek(switchTask.getId());
      MapContext newContext = new MapContext(context.asMap());
      newContext.put(aTaskExecution.getName(), aTaskExecution.getOutput());
      contextRepository.push(switchTask.getId(), newContext);
    }
    
    List<MapObject> tasks = resolveCase(switchTask);
    if(aTaskExecution.getTaskNumber()<tasks.size()) {
      MapObject task = tasks.get(aTaskExecution.getTaskNumber());
      SimpleTaskExecution execution = SimpleTaskExecution.createFromMap(task);
      execution.setId(UUIDGenerator.generate());
      execution.setStatus(TaskStatus.CREATED);
      execution.setCreateTime(new Date());
      execution.setTaskNumber(aTaskExecution.getTaskNumber()+1);
      execution.setJobId(switchTask.getJobId());
      execution.setParentId(switchTask.getId());
      execution.setPriority(switchTask.getPriority());
      MapContext context = new MapContext (contextRepository.peek(switchTask.getId()));
      contextRepository.push(execution.getId(), context);
      TaskExecution evaluatedExecution = taskEvaluator.evaluate(execution, context);
      taskExecutionRepo.create(evaluatedExecution);
      taskDispatcher.dispatch(evaluatedExecution);
    }
    // no more tasks to execute -- complete the switch
    else {
      // if switch is root level, update the job's context
      if(switchTask.getParentId() == null) {
        Context parentContext = contextRepository.peek(switchTask.getJobId());
        Context thisContext = contextRepository.peek(switchTask.getId());
        MapContext newContext = new MapContext(parentContext);
        newContext.putAll(thisContext.asMap());
        contextRepository.push(aTaskExecution.getJobId(), newContext);
      }
      // otherwise update the its parent's context
      else {
        Context parentContext = contextRepository.peek(switchTask.getParentId());
        Context thisContext = contextRepository.peek(switchTask.getId());
        MapContext newContext = new MapContext(parentContext);
        newContext.putAll(thisContext.asMap());
        contextRepository.push(switchTask.getParentId(), newContext);
      }
      switchTask.setEndTime(new Date());
      switchTask.setExecutionTime(switchTask.getEndTime().getTime()-switchTask.getStartTime().getTime());
      taskCompletionHandler.handle(switchTask);
    }
  }

  private List<MapObject> resolveCase (TaskExecution aSwitch) {
    Object expression = aSwitch.getRequired("expression");
    List<MapObject> cases = aSwitch.getList("cases", MapObject.class);
    Assert.notNull(cases,"you must specify 'cases' in a switch statement");
    for(MapObject oneCase : cases) {
      Object key = oneCase.getRequired("key");
      List<MapObject> tasks = oneCase.getList("tasks",MapObject.class);
      if(key.equals(expression)) {
        return tasks;
      }
    }
    return aSwitch.getList("default", MapObject.class,Collections.emptyList());
  }

  @Override
  public boolean canHandle (TaskExecution aTaskExecution) {
    String parentId = aTaskExecution.getParentId();
    if(parentId!=null) {
      TaskExecution parentExecution = taskExecutionRepo.findOne(parentId);
      return parentExecution.getType().equals(DSL.SWITCH);
    }
    return false;
  }

}
