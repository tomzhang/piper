/* 
 * Copyright (C) Creactiviti LLC - All Rights Reserved
 * Unauthorized copying of this file, via any medium is strictly prohibited
 * Proprietary and confidential
 * Written by Arik Cohen <arik@creactiviti.com>, June 2017
 */
package com.creactiviti.piper.core.task;

import java.util.Date;
import java.util.List;
import java.util.Map;

import org.springframework.util.Assert;

import com.creactiviti.piper.core.DSL;
import com.creactiviti.piper.core.context.ContextRepository;
import com.creactiviti.piper.core.context.MapContext;
import com.creactiviti.piper.core.messenger.Messenger;
import com.creactiviti.piper.core.messenger.Queues;
import com.creactiviti.piper.core.uuid.UUIDGenerator;

/**
 * @author Arik Cohen
 * @since Jun 4, 2017
 */
public class MapTaskDispatcher implements TaskDispatcher<TaskExecution>, TaskDispatcherResolver {

  private final TaskDispatcher taskDispatcher;
  private final TaskEvaluator taskEvaluator = new SpelTaskEvaluator();
  private final TaskExecutionRepository taskExecutionRepo;
  private final Messenger messenger;
  private final ContextRepository contextRepository;
  private final CounterRepository counterRepository;

  public MapTaskDispatcher (TaskDispatcher aTaskDispatcher, TaskExecutionRepository aTaskExecutionRepo, Messenger aMessenger, ContextRepository aContextRepository, CounterRepository aCounterRepository) {
    taskDispatcher = aTaskDispatcher;
    taskExecutionRepo = aTaskExecutionRepo;
    messenger = aMessenger;
    contextRepository = aContextRepository;
    counterRepository = aCounterRepository;
  }

  @Override
  public void dispatch (TaskExecution aTask) {
    List<Object> list = aTask.getList("list", Object.class);
    Assert.notNull(list,"'list' property can't be null");
    Map<String, Object> iteratee = aTask.getMap("iteratee");
    Assert.notNull(list,"'iteratee' property can't be null");
    
    SimpleTaskExecution parentMapTask = SimpleTaskExecution.createForUpdate(aTask);
    parentMapTask.setStartTime(new Date ());
    parentMapTask.setStatus(TaskStatus.STARTED);
    taskExecutionRepo.merge(parentMapTask);
    
    if(list.size() > 0) {
      counterRepository.set(aTask.getId(), list.size());
      for(int i=0; i<list.size(); i++) {
        Object item = list.get(i);
        SimpleTaskExecution mapTask = SimpleTaskExecution.createFromMap(iteratee);
        mapTask.setId(UUIDGenerator.generate());
        mapTask.setParentId(aTask.getId());
        mapTask.setStatus(TaskStatus.CREATED);
        mapTask.setJobId(aTask.getJobId());
        mapTask.setCreateTime(new Date());
        mapTask.setPriority(aTask.getPriority());
        mapTask.setTaskNumber(i+1);
        MapContext context = new MapContext (contextRepository.peek(aTask.getId()));
        context.set(aTask.getString("itemVar","item"), item);
        context.set(aTask.getString("itemIndex","itemIndex"), i);
        contextRepository.push(mapTask.getId(), context);
        TaskExecution evaluatedEachTask = taskEvaluator.evaluate(mapTask, context);
        taskExecutionRepo.create(evaluatedEachTask);
        taskDispatcher.dispatch(evaluatedEachTask);
      }
    }
    else {
      SimpleTaskExecution completion = SimpleTaskExecution.createForUpdate(aTask);
      completion.setEndTime(new Date());
      messenger.send(Queues.COMPLETIONS, completion);
    }
  }

  @Override
  public TaskDispatcher resolve (Task aTask) {
    if(aTask.getType().equals(DSL.MAP)) {
      return this;
    }
    return null;
  }

}
