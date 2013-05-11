package com.twitter.mesos.scheduler;

import java.util.Comparator;
import java.util.EnumSet;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import javax.annotation.Nullable;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.base.Predicates;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.common.util.concurrent.Atomics;
import com.google.inject.Inject;

import org.apache.mesos.Protos.SlaveID;

import com.twitter.common.base.Closure;
import com.twitter.common.stats.Stats;
import com.twitter.common.util.Clock;
import com.twitter.mesos.Tasks;
import com.twitter.mesos.gen.AssignedTask;
import com.twitter.mesos.gen.Identity;
import com.twitter.mesos.gen.ScheduleStatus;
import com.twitter.mesos.gen.ScheduledTask;
import com.twitter.mesos.gen.ShardUpdateResult;
import com.twitter.mesos.gen.TaskQuery;
import com.twitter.mesos.gen.TwitterTaskInfo;
import com.twitter.mesos.gen.UpdateResult;
import com.twitter.mesos.gen.storage.JobUpdateConfiguration;
import com.twitter.mesos.gen.storage.TaskUpdateConfiguration;
import com.twitter.mesos.scheduler.TransactionalStorage.SideEffectWork;
import com.twitter.mesos.scheduler.TransactionalStorage.TransactionFinalizer;
import com.twitter.mesos.scheduler.events.PubsubEvent;
import com.twitter.mesos.scheduler.storage.Storage;
import com.twitter.mesos.scheduler.storage.Storage.MutableStoreProvider;
import com.twitter.mesos.scheduler.storage.Storage.StorageException;
import com.twitter.mesos.scheduler.storage.Storage.StoreProvider;
import com.twitter.mesos.scheduler.storage.Storage.Work;
import com.twitter.mesos.scheduler.storage.TaskStore;
import com.twitter.mesos.scheduler.storage.UpdateStore;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.collect.Iterables.transform;

import static com.twitter.common.base.MorePreconditions.checkNotBlank;
import static com.twitter.mesos.Tasks.SCHEDULED_TO_SHARD_ID;
import static com.twitter.mesos.gen.ScheduleStatus.INIT;
import static com.twitter.mesos.gen.ScheduleStatus.KILLING;
import static com.twitter.mesos.gen.ScheduleStatus.PENDING;
import static com.twitter.mesos.gen.ScheduleStatus.ROLLBACK;
import static com.twitter.mesos.gen.ScheduleStatus.UNKNOWN;
import static com.twitter.mesos.gen.ScheduleStatus.UPDATING;
import static com.twitter.mesos.scheduler.Shards.GET_NEW_CONFIG;
import static com.twitter.mesos.scheduler.Shards.GET_ORIGINAL_CONFIG;

/**
 * Manager of all persistence-related operations for the scheduler.  Acts as a controller for
 * persisted state machine transitions, and their side-effects.
 *
 * TODO(William Farner): Re-evaluate thread safety here, specifically risk of races that
 * modify managerState.
 */
public class StateManagerImpl implements StateManager {
  private static final Logger LOG = Logger.getLogger(StateManagerImpl.class.getName());

  @VisibleForTesting
  final TransactionalStorage txStorage;

  // TODO(William Farner): Eliminate this and update all callers to use Storage directly.
  interface ReadOnlyStorage {
    <T, E extends Exception> T doInTransaction(Work<T, E> work) throws StorageException, E;
  }
  private final ReadOnlyStorage readOnlyStorage;

  // Work queue to receive state machine side effect work.
  // Items are sorted to place DELETE entries last.  This is to ensure that within a transaction,
  // a delete is always processed after a state transition.
  private final Queue<WorkEntry> workQueue = new PriorityQueue<WorkEntry>(10,
      new Comparator<WorkEntry>() {
        @Override public int compare(WorkEntry a, WorkEntry b) {
          if ((a.command == WorkCommand.DELETE) != (b.command == WorkCommand.DELETE)) {
            return (a.command == WorkCommand.DELETE) ? 1 : -1;
          } else {
            return 0;
          }
        }
      });

  // Adapt the work queue into a sink.
  private final TaskStateMachine.WorkSink workSink = new TaskStateMachine.WorkSink() {
      @Override public void addWork(WorkCommand work, TaskStateMachine stateMachine,
          Closure<ScheduledTask> mutation) {
        workQueue.add(new WorkEntry(work, stateMachine, mutation));
      }
    };

  private final Function<TwitterTaskInfo, ScheduledTask> taskCreator =
      new Function<TwitterTaskInfo, ScheduledTask>() {
        @Override public ScheduledTask apply(TwitterTaskInfo task) {
          return new ScheduledTask()
              .setStatus(INIT)
              .setAssignedTask(new AssignedTask().setTaskId(generateTaskId(task)).setTask(task));
        }
      };

  private final Driver driver;
  private final Clock clock;

  /**
   * An item of work on the work queue.
   */
  private static class WorkEntry {
    final WorkCommand command;
    final TaskStateMachine stateMachine;
    final Closure<ScheduledTask> mutation;

    WorkEntry(WorkCommand command, TaskStateMachine stateMachine, Closure<ScheduledTask> mutation) {
      this.command = command;
      this.stateMachine = stateMachine;
      this.mutation = mutation;
    }
  }

  @Inject
  StateManagerImpl(
      final Storage storage,
      final Clock clock,
      Driver driver,
      Closure<PubsubEvent> taskEventSink) {

    checkNotNull(storage);
    this.clock = checkNotNull(clock);

    TransactionFinalizer finalizer = new TransactionFinalizer() {
      @Override public void finalize(SideEffectWork<?, ?> work, MutableStoreProvider store) {
        processWorkQueueInTransaction(work, store);
      }
    };

    txStorage = new TransactionalStorage(storage, finalizer, taskEventSink);
    readOnlyStorage = new ReadOnlyStorage() {
      @Override public <T, E extends Exception> T doInTransaction(Work<T, E> work)
          throws StorageException, E {
        return storage.doInTransaction(work);
      }
    };

    this.driver = checkNotNull(driver);

    Stats.exportSize("work_queue_depth", workQueue);
  }

  /**
   * Inserts new tasks into the store.
   *
   * @param tasks Tasks to insert.
   * @return Generated task IDs for the tasks inserted.
   */
  Set<String> insertTasks(Set<TwitterTaskInfo> tasks) {
    checkNotNull(tasks);

    final Set<ScheduledTask> scheduledTasks = ImmutableSet.copyOf(transform(tasks, taskCreator));

    txStorage.doInWriteTransaction(txStorage.new NoResultSideEffectWork() {
      @Override protected void execute(MutableStoreProvider storeProvider) {
        storeProvider.getTaskStore().saveTasks(scheduledTasks);

        for (ScheduledTask task : scheduledTasks) {
          createStateMachine(task).updateState(PENDING);
        }
      }
    });

    return Tasks.ids(scheduledTasks);
  }

  /**
   * Thrown when an update fails.
   */
  static class UpdateException extends Exception {
    public UpdateException(String msg) {
      super(msg);
    }
    public UpdateException(String msg, Throwable cause) {
      super(msg, cause);
    }
  }

  /**
   * Registers a new update.
   *
   * @param role Role to register an update for.
   * @param job Job to register an update for.
   * @param updatedTasks Updated Task information to be registered.
   * @throws UpdateException If no active tasks are found for the job, or if an update for the job
   *     is already in progress.
   * @return A unique string identifying the update.
   */
  String registerUpdate(
      final String role,
      final String job,
      final Set<TwitterTaskInfo> updatedTasks) throws UpdateException {

    checkNotBlank(role);
    checkNotBlank(job);
    checkNotBlank(updatedTasks);

    return txStorage.doInWriteTransaction(txStorage.new SideEffectWork<String, UpdateException>() {
      @Override public String apply(MutableStoreProvider storeProvider) throws UpdateException {
        assertNotUpdatingOrRollingBack(role, job, storeProvider.getTaskStore());

        String jobKey = Tasks.jobKey(role, job);
        Set<TwitterTaskInfo> existingTasks = ImmutableSet.copyOf(Iterables.transform(
            storeProvider.getTaskStore().fetchTasks(Query.jobScoped(role, job).active()),
            Tasks.SCHEDULED_TO_INFO));

        if (existingTasks.isEmpty()) {
          throw new UpdateException("No active tasks found for job " + jobKey);
        }

        UpdateStore.Mutable updateStore = storeProvider.getUpdateStore();
        if (updateStore.fetchJobUpdateConfig(role, job).isPresent()) {
          throw new UpdateException("Update already in progress for " + jobKey);
        }

        Map<Integer, TwitterTaskInfo> oldShards = Maps.uniqueIndex(existingTasks,
            Tasks.INFO_TO_SHARD_ID);
        Map<Integer, TwitterTaskInfo> newShards = Maps.uniqueIndex(updatedTasks,
            Tasks.INFO_TO_SHARD_ID);

        ImmutableSet.Builder<TaskUpdateConfiguration> shardConfigBuilder = ImmutableSet.builder();
        for (int shard : Sets.union(oldShards.keySet(), newShards.keySet())) {
          shardConfigBuilder.add(
              new TaskUpdateConfiguration(oldShards.get(shard), newShards.get(shard)));
        }

        String updateToken = UUID.randomUUID().toString();
        updateStore.saveJobUpdateConfig(
            new JobUpdateConfiguration(role, job, updateToken, shardConfigBuilder.build()));
        return updateToken;
      }
    });
  }

  private static final Set<ScheduleStatus> UPDATE_IN_PROGRESS = EnumSet.of(UPDATING, ROLLBACK);
  private static void assertNotUpdatingOrRollingBack(
      String role,
      String jobName,
      TaskStore taskStore) throws UpdateException {

    Query.Builder query = Query.jobScoped(role, jobName).byStatus(UPDATE_IN_PROGRESS);
    if (!taskStore.fetchTaskIds(query).isEmpty()) {
      throw new UpdateException("Unable to proceed until UPDATING and ROLLBACK tasks complete.");
    }
  }

  /**
   * Completes an in-progress update.
   *
   * @param identity The job owner and invoking user.
   * @param job Job to finish updating.
   * @param updateToken Token associated with the update.  If present, the token must match the
   *     the stored token for the update.
   * @param result The result of the update.
   * @param throwIfMissing If {@code true}, this throws UpdateException when the update is missing.
   * @return {@code true} if the update was finished, false if nonexistent.
   * @throws UpdateException If an update is not in-progress for the job, or the non-null token
   *     does not match the stored token.
   */
  boolean finishUpdate(
      final Identity identity,
      final String job,
      final Optional<String> updateToken,
      final UpdateResult result,
      final boolean throwIfMissing) throws UpdateException {

    checkNotNull(identity);
    final String role = identity.getRole();
    final String updatingUser = identity.getUser();
    checkNotBlank(role);
    checkNotBlank(updatingUser);
    checkNotBlank(job);

    return txStorage.doInWriteTransaction(txStorage.new SideEffectWork<Boolean, UpdateException>() {
      @Override public Boolean apply(MutableStoreProvider storeProvider) throws UpdateException {
        assertNotUpdatingOrRollingBack(role, job, storeProvider.getTaskStore());

        UpdateStore.Mutable updateStore = storeProvider.getUpdateStore();

        String jobKey = Tasks.jobKey(role, job);

        // Since we store all shards in a job with the same token, we can just check shard 0,
        // which is always guaranteed to exist for a job.
        Optional<JobUpdateConfiguration> jobConfig = updateStore.fetchJobUpdateConfig(role, job);
        if (!jobConfig.isPresent()) {
          if (throwIfMissing) {
            throw new UpdateException("Update does not exist for " + jobKey);
          }
          return false;
        }

        if (updateToken.isPresent()
            && !updateToken.get().equals(jobConfig.get().getUpdateToken())) {
          throw new UpdateException("Invalid update token for " + jobKey);
        }

        if (EnumSet.of(UpdateResult.SUCCESS, UpdateResult.FAILED).contains(result)) {
          // Kill any shards that were removed during the update or rollback.
          Function<TaskUpdateConfiguration, TwitterTaskInfo> removedSelector =
              (result == UpdateResult.SUCCESS) ? GET_NEW_CONFIG : GET_ORIGINAL_CONFIG;
          for (Integer shard : fetchRemovedShards(jobConfig.get(), removedSelector)) {
            changeState(
                Query.shardScoped(role, job, shard).active().get(),
                KILLING,
                Optional.of("Removed during update by " + updatingUser));
          }
        }

        updateStore.removeShardUpdateConfigs(role, job);
        return true;
      }
    });
  }

  private static final Function<TaskUpdateConfiguration, Integer> GET_SHARD_ID =
      new Function<TaskUpdateConfiguration, Integer>() {
        @Override public Integer apply(TaskUpdateConfiguration config) {
          TwitterTaskInfo task = (config.getOldConfig() != null)
              ? config.getOldConfig()
              : config.getNewConfig();
          return task.getShardId();
        }
      };

  private Set<Integer> fetchRemovedShards(
      JobUpdateConfiguration jobConfig,
      Function<TaskUpdateConfiguration, TwitterTaskInfo> configSelector) {

    return FluentIterable.from(jobConfig.getConfigs())
        .filter(Predicates.compose(Predicates.isNull(), configSelector))
        .transform(GET_SHARD_ID)
        .toSet();
  }

  /**
   * Performs a simple state change, transitioning all tasks matching a query to the given
   * state.
   * No audit message will be applied with the transition.
   *
   * @param query Query to perform, the results of which will be modified.
   * @param newState State to move the resulting tasks into.
   * @return the number of successful state changes.
   */
  @VisibleForTesting
  int changeState(TaskQuery query, ScheduleStatus newState) {
    return changeState(query, stateUpdater(newState));
  }

  @Override
  public int changeState(
      TaskQuery query,
      final ScheduleStatus newState,
      final Optional<String> auditMessage) {

    return changeState(query, new Function<TaskStateMachine, Boolean>() {
      @Override public Boolean apply(TaskStateMachine stateMachine) {
        return stateMachine.updateState(newState, auditMessage);
      }
    });
  }

  @Override
  public AssignedTask assignTask(
      String taskId,
      String slaveHost,
      SlaveID slaveId,
      Set<Integer> assignedPorts) {

    checkNotBlank(taskId);
    checkNotBlank(slaveHost);
    checkNotNull(assignedPorts);

    TaskAssignMutation mutation = assignHost(slaveHost, slaveId, assignedPorts);
    changeState(Query.byId(taskId), mutation);

    return mutation.getAssignedTask();
  }

  /**
   * Fetches all tasks that match a query.
   * TODO(William Farner): Remove this method and update callers to invoke storage directly.
   *
   * @param query Query to perform.
   * @return A read-only view of the tasks matching the query.
   */
  @Override
  public Set<ScheduledTask> fetchTasks(final TaskQuery query) {
    checkNotNull(query);

    return readOnlyStorage.doInTransaction(new Work.Quiet<Set<ScheduledTask>>() {
      @Override public Set<ScheduledTask> apply(StoreProvider storeProvider) {
        return storeProvider.getTaskStore().fetchTasks(query);
      }
    });
  }

  @VisibleForTesting
  static void putResults(
      ImmutableMap.Builder<Integer, ShardUpdateResult> builder,
      ShardUpdateResult result,
      Iterable<Integer> shardIds) {

    for (int shardId : shardIds) {
      builder.put(shardId, result);
    }
  }

  private static final Predicate<ScheduledTask> NOT_UPDATING = new Predicate<ScheduledTask>() {
    @Override public boolean apply(ScheduledTask task) {
      return task.getStatus() != UPDATING && task.getStatus() != ROLLBACK;
    }
  };

  private Set<Integer> getChangedShards(Set<ScheduledTask> tasks, Set<TwitterTaskInfo> compareTo) {
    Set<TwitterTaskInfo> existingTasks = FluentIterable.from(tasks)
        .transform(Tasks.SCHEDULED_TO_INFO)
        .toSet();
    Set<TwitterTaskInfo> changedTasks = Sets.difference(compareTo, existingTasks);
    return FluentIterable.from(changedTasks)
        .transform(Tasks.INFO_TO_SHARD_ID)
        .toSet();
  }

  Map<Integer, ShardUpdateResult> modifyShards(
      final Identity identity,
      final String jobName,
      final Set<Integer> shards,
      final String updateToken,
      boolean updating) throws UpdateException {

    final Function<TaskUpdateConfiguration, TwitterTaskInfo> configSelector = updating
        ? GET_NEW_CONFIG
        : GET_ORIGINAL_CONFIG;
    final ScheduleStatus modifyingState;
    final String role = identity.getRole();
    final String auditMessage;
    if (updating) {
      modifyingState = UPDATING;
      auditMessage = "Updated by " + identity.getUser();
    } else {
      modifyingState = ROLLBACK;
      auditMessage = "Rolled back by " + identity.getUser();
    }

    return txStorage.doInWriteTransaction(
        txStorage.new SideEffectWork<Map<Integer, ShardUpdateResult>, UpdateException>() {
          @Override public Map<Integer, ShardUpdateResult> apply(
              MutableStoreProvider store) throws UpdateException {

            ImmutableMap.Builder<Integer, ShardUpdateResult> result = ImmutableMap.builder();

            String jobKey = Tasks.jobKey(role, jobName);
            Optional<JobUpdateConfiguration> updateConfig =
                store.getUpdateStore().fetchJobUpdateConfig(role, jobName);
            if (!updateConfig.isPresent()) {
              throw new UpdateException("No active update found for " + jobKey);
            }

            if (!updateConfig.get().getUpdateToken().equals(updateToken)) {
              throw new UpdateException("Invalid update token for " + jobKey);
            }

            Set<ScheduledTask> tasks =
                store.getTaskStore().fetchTasks(Query
                    .shardScoped(role, jobName, shards)
                    .active()
                    .get());

            // Extract any shard IDs that are being added as a part of this stage in the update.
            Set<Integer> newShardIds = Sets.difference(shards,
                ImmutableSet.copyOf(Iterables.transform(tasks, SCHEDULED_TO_SHARD_ID)));

            if (!newShardIds.isEmpty()) {
              Set<TwitterTaskInfo> newTasks = fetchTaskUpdateConfigs(
                  updateConfig.get(),
                  newShardIds,
                  configSelector);
              Set<Integer> unrecognizedShards = Sets.difference(newShardIds,
                  ImmutableSet.copyOf(Iterables.transform(newTasks, Tasks.INFO_TO_SHARD_ID)));
              if (!unrecognizedShards.isEmpty()) {
                throw new UpdateException(
                    "Cannot update unrecognized shards " + unrecognizedShards);
              }

              // Create new tasks, so they will be moved into the PENDING state.
              insertTasks(newTasks);
              putResults(result, ShardUpdateResult.ADDED, newShardIds);
            }

            Set<Integer> updateShardIds = Sets.difference(shards, newShardIds);
            if (!updateShardIds.isEmpty()) {
              Set<TwitterTaskInfo> targetConfigs = fetchTaskUpdateConfigs(
                  updateConfig.get(),
                  updateShardIds,
                  configSelector);
              // Filter tasks in UPDATING/ROLLBACK to obtain the changed shards. This is done so
              // that these tasks are either rolled back or updated. If not, when a task in UPDATING
              // is later KILLED and a new shard is created with the updated configuration it
              // appears as if the rollback completed successfully.
              Set<ScheduledTask> notUpdating = FluentIterable.from(tasks)
                  .filter(NOT_UPDATING)
                  .toSet();
              Set<Integer> changedShards = getChangedShards(notUpdating, targetConfigs);
              if (!changedShards.isEmpty()) {
                // Initiate update on the existing shards.
                // TODO(William Farner): The additional query could be avoided here.
                //                       Consider allowing state changes on tasks by task ID.
                changeState(
                    Query.shardScoped(role, jobName, changedShards).active().get(),
                    modifyingState,
                    Optional.of(auditMessage));
                putResults(result, ShardUpdateResult.RESTARTING, changedShards);
              }
              putResults(
                  result,
                  ShardUpdateResult.UNCHANGED,
                  Sets.difference(updateShardIds, changedShards));
            }

            return result.build();
          }
        });
  }

  private Optional<TaskUpdateConfiguration> fetchShardUpdateConfig(
      UpdateStore updateStore,
      String role,
      String job,
      int shard) {

    Optional<JobUpdateConfiguration> optional = updateStore.fetchJobUpdateConfig(role, job);
    if (optional.isPresent()) {
      Set<TaskUpdateConfiguration> matches =
          fetchShardUpdateConfigs(optional.get(), ImmutableSet.of(shard));
      return Optional.fromNullable(Iterables.getOnlyElement(matches, null));
    } else {
      return Optional.absent();
    }
  }

  private static final Function<TaskUpdateConfiguration, Integer> GET_SHARD =
      new Function<TaskUpdateConfiguration, Integer>() {
        @Override public Integer apply(TaskUpdateConfiguration config) {
          return config.isSetOldConfig()
              ? config.getOldConfig().getShardId()
              : config.getNewConfig().getShardId();
        }
      };

  private Set<TaskUpdateConfiguration> fetchShardUpdateConfigs(
      JobUpdateConfiguration config,
      Set<Integer> shards) {

    return ImmutableSet.copyOf(Iterables.filter(config.getConfigs(),
        Predicates.compose(Predicates.in(shards), GET_SHARD)));
  }

  /**
   * Fetches the task configurations for shards in the context of an in-progress job update.
   *
   * @param shards Shards within a job to fetch.
   * @return The task information of the shard.
   */
  private Set<TwitterTaskInfo> fetchTaskUpdateConfigs(
      JobUpdateConfiguration config,
      final Set<Integer> shards,
      final Function<TaskUpdateConfiguration, TwitterTaskInfo> configSelector) {

    checkNotNull(config);
    checkNotBlank(shards);
    return FluentIterable.from(fetchShardUpdateConfigs(config, shards))
        .transform(configSelector)
        .filter(Predicates.notNull())
        .toSet();
  }

  private int changeStateInTransaction(
      Set<String> taskIds,
      Function<TaskStateMachine, Boolean> stateChange) {

    int count = 0;
    for (TaskStateMachine stateMachine : getStateMachines(taskIds).values()) {
      if (stateChange.apply(stateMachine)) {
        ++count;
      }
    }
    return count;
  }

  private int changeState(
      final TaskQuery query,
      final Function<TaskStateMachine, Boolean> stateChange) {

    return txStorage.doInWriteTransaction(txStorage.new QuietSideEffectWork<Integer>() {
      @Override public Integer apply(MutableStoreProvider storeProvider) {
        return changeStateInTransaction(
            storeProvider.getTaskStore().fetchTaskIds(query), stateChange);
      }
    });
  }

  private static Function<TaskStateMachine, Boolean> stateUpdater(final ScheduleStatus state) {
    return new Function<TaskStateMachine, Boolean>() {
      @Override public Boolean apply(TaskStateMachine stateMachine) {
        return stateMachine.updateState(state);
      }
    };
  }

  private interface TaskAssignMutation extends Function<TaskStateMachine, Boolean> {
    AssignedTask getAssignedTask();
  }

  private TaskAssignMutation assignHost(
      final String slaveHost,
      final SlaveID slaveId,
      final Set<Integer> assignedPorts) {

    final Closure<ScheduledTask> mutation = new Closure<ScheduledTask>() {
      @Override public void execute(ScheduledTask task) {
        AssignedTask assigned = task.getAssignedTask();
        assigned.setAssignedPorts(CommandLineExpander.getNameMappedPorts(
            assigned.getTask().getRequestedPorts(), assignedPorts));
        assigned.setSlaveHost(slaveHost)
            .setSlaveId(slaveId.getValue());
      }
    };

    return new TaskAssignMutation() {
      AtomicReference<AssignedTask> assignedTask = Atomics.newReference();
      @Override public AssignedTask getAssignedTask() {
        return assignedTask.get();
      }

      @Override public Boolean apply(final TaskStateMachine stateMachine) {
        Closure<ScheduledTask> wrapper = new Closure<ScheduledTask>() {
          @Override public void execute(ScheduledTask task) {
            mutation.execute(task);
            Preconditions.checkState(
                assignedTask.compareAndSet(null, task.getAssignedTask()),
                "More than one result was found for an identity query.");
          }
        };
        return stateMachine.updateState(ScheduleStatus.ASSIGNED, wrapper);
      }
    };
  }

  // Supplier that checks if there is an active update for a job.
  private Supplier<Boolean> taskUpdateChecker(final String role, final String job) {
    return new Supplier<Boolean>() {
      @Override public Boolean get() {
        return readOnlyStorage.doInTransaction(new Work.Quiet<Boolean>() {
          @Override public Boolean apply(StoreProvider storeProvider) {
            return storeProvider.getUpdateStore().fetchJobUpdateConfig(role, job).isPresent();
          }
        });
      }
    };
  }

  /**
   * Creates a new task ID that is permanently unique (not guaranteed, but highly confident),
   * and by default sorts in chronological order.
   *
   * @param task Task that an ID is being generated for.
   * @return New task ID.
   */
  private String generateTaskId(TwitterTaskInfo task) {
    String sep = "-";
    return new StringBuilder()
        .append(clock.nowMillis())               // Allows chronological sorting.
        .append(sep)
        .append(task.getOwner().getRole())       // Identification and collision prevention.
        .append(sep)
        .append(task.getJobName())
        .append(sep)
        .append(task.getShardId())               // Collision prevention within job.
        .append(sep)
        .append(UUID.randomUUID())               // Just-in-case collision prevention.
        .toString().replaceAll("[^\\w-]", sep);  // Constrain character set.
  }

  private void processWorkQueueInTransaction(
      SideEffectWork<?, ?> sideEffectWork,
      MutableStoreProvider storeProvider) {

    for (final WorkEntry work : Iterables.consumingIterable(workQueue)) {
      final TaskStateMachine stateMachine = work.stateMachine;

      if (work.command == WorkCommand.KILL) {
        driver.killTask(stateMachine.getTaskId());
      } else {
        TaskStore.Mutable taskStore = storeProvider.getTaskStore();
        String taskId = stateMachine.getTaskId();
        TaskQuery idQuery = Query.byId(taskId);

        switch (work.command) {
          case RESCHEDULE:
            ScheduledTask task =
                Iterables.getOnlyElement(taskStore.fetchTasks(idQuery)).deepCopy();
            task.getAssignedTask().unsetSlaveId();
            task.getAssignedTask().unsetSlaveHost();
            task.getAssignedTask().unsetAssignedPorts();
            task.unsetTaskEvents();
            task.setAncestorId(taskId);
            String newTaskId = generateTaskId(task.getAssignedTask().getTask());
            task.getAssignedTask().setTaskId(newTaskId);

            LOG.info("Task being rescheduled: " + taskId);

            taskStore.saveTasks(ImmutableSet.of(task));

            createStateMachine(task).updateState(PENDING, Optional.of("Rescheduled"));
            TwitterTaskInfo taskInfo = task.getAssignedTask().getTask();
            sideEffectWork.addTaskEvent(
                new PubsubEvent.TaskRescheduled(
                    taskInfo.getOwner().getRole(),
                    taskInfo.getJobName(),
                    taskInfo.getShardId()));
            break;

          case UPDATE:
          case ROLLBACK:
            maybeRescheduleForUpdate(storeProvider, taskId, work.command == WorkCommand.ROLLBACK);
            break;

          case UPDATE_STATE:
            taskStore.mutateTasks(idQuery, new Closure<ScheduledTask>() {
              @Override public void execute(ScheduledTask task) {
                task.setStatus(stateMachine.getState());
                work.mutation.execute(task);
              }
            });
            sideEffectWork.addTaskEvent(
                new PubsubEvent.TaskStateChange(
                    Iterables.getOnlyElement(taskStore.fetchTasks(idQuery)),
                    stateMachine.getPreviousState()));
            break;

          case DELETE:
            taskStore.deleteTasks(ImmutableSet.of(taskId));
            break;

          case INCREMENT_FAILURES:
            taskStore.mutateTasks(idQuery, new Closure<ScheduledTask>() {
              @Override public void execute(ScheduledTask task) {
                task.setFailureCount(task.getFailureCount() + 1);
              }
            });
            break;

          default:
            LOG.severe("Unrecognized work command type " + work.command);
        }
      }
    }
  }

  private void maybeRescheduleForUpdate(
      MutableStoreProvider storeProvider,
      String taskId,
      boolean rollingBack) {

    TaskStore.Mutable taskStore = storeProvider.getTaskStore();

    TwitterTaskInfo oldConfig = Tasks.SCHEDULED_TO_INFO.apply(
        Iterables.getOnlyElement(taskStore.fetchTasks(Query.byId(taskId))));

    Optional<TaskUpdateConfiguration> optional = fetchShardUpdateConfig(
        storeProvider.getUpdateStore(),
        oldConfig.getOwner().getRole(),
        oldConfig.getJobName(),
        oldConfig.getShardId());

    // TODO(Sathya): Figure out a way to handle race condition when finish update is called
    //     before ROLLBACK

    if (!optional.isPresent()) {
      LOG.warning("No update configuration found for key " + Tasks.jobKey(oldConfig)
          + " shard " + oldConfig.getShardId() + " : Assuming update has finished.");
      return;
    }

    TaskUpdateConfiguration updateConfig = optional.get();
    TwitterTaskInfo newConfig =
        rollingBack ? updateConfig.getOldConfig() : updateConfig.getNewConfig();
    if (newConfig == null) {
      // The updated configuration removed the shard, nothing to reschedule.
      return;
    }

    ScheduledTask newTask = taskCreator.apply(newConfig).setAncestorId(taskId);
    taskStore.saveTasks(ImmutableSet.of(newTask));
    createStateMachine(newTask)
        .updateState(
            PENDING,
            Optional.of("Rescheduled after " + (rollingBack ? "rollback." : "update.")));
  }

  private Map<String, TaskStateMachine> getStateMachines(final Set<String> taskIds) {
    return readOnlyStorage.doInTransaction(new Work.Quiet<Map<String, TaskStateMachine>>() {
      @Override public Map<String, TaskStateMachine> apply(StoreProvider storeProvider) {
        Set<ScheduledTask> tasks = storeProvider.getTaskStore().fetchTasks(Query.byId(taskIds));
        Map<String, ScheduledTask> existingTasks = Maps.uniqueIndex(
            tasks,
            new Function<ScheduledTask, String>() {
              @Override public String apply(ScheduledTask input) {
                return input.getAssignedTask().getTaskId();
              }
            });

        ImmutableMap.Builder<String, TaskStateMachine> builder = ImmutableMap.builder();
        for (String taskId : taskIds) {
          // Pass null get() values through.
          builder.put(taskId, getStateMachine(taskId, existingTasks.get(taskId)));
        }
        return builder.build();
      }
    });
  }

  private TaskStateMachine getStateMachine(String taskId, @Nullable ScheduledTask task) {
    if (task != null) {
      return createStateMachine(task, task.getStatus());
    }

    // The task is unknown, not present in storage.
    TaskStateMachine stateMachine = new TaskStateMachine(
        taskId,
        null,
        // The task is unknown, so there is no matching task to fetch.
        null,
        null,
        // Since the task doesn't exist, its job cannot be updating.
        Suppliers.ofInstance(false),
        workSink,
        clock,
        INIT);
    stateMachine.updateState(UNKNOWN);
    return stateMachine;
  }

  private TaskStateMachine createStateMachine(ScheduledTask task) {
    return createStateMachine(task, INIT);
  }

  private TaskStateMachine createStateMachine(ScheduledTask task, ScheduleStatus initialState) {
    String role = Tasks.getRole(task);
    String job = Tasks.getJob(task);
    return new TaskStateMachine(
        Tasks.id(task),
        role,
        job,
        task,
        taskUpdateChecker(role, job),
        workSink,
        clock,
        initialState);
  }
}
