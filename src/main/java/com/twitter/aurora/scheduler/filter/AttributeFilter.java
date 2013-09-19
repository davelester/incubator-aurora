/*
 * Copyright 2013 Twitter, Inc.
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
package com.twitter.aurora.scheduler.filter;

import java.util.Set;

import com.google.common.base.Function;
import com.google.common.base.Predicate;
import com.google.common.base.Predicates;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;

import com.twitter.aurora.gen.Attribute;
import com.twitter.aurora.gen.JobKey;
import com.twitter.aurora.gen.ScheduledTask;
import com.twitter.aurora.gen.ValueConstraint;
import com.twitter.aurora.scheduler.base.Tasks;
import com.twitter.aurora.scheduler.filter.SchedulingFilterImpl.AttributeLoader;

/**
 * Utility class that matches attributes to constraints.
 */
final class AttributeFilter {

  private static final Function<Attribute, Set<String>> GET_VALUES =
      new Function<Attribute, Set<String>>() {
        @Override public Set<String> apply(Attribute attribute) {
          return attribute.getValues();
        }
      };

  private AttributeFilter() {
    // Utility class.
  }

  /**
   * Tests whether a constraint is satisfied by attributes.
   *
   * @param attributes Host attributes.
   * @param constraint Constraint to match.
   * @return {@code true} if the attribute satisfies the constraint, {@code false} otherwise.
   */
  static boolean matches(Set<Attribute> attributes, ValueConstraint constraint) {
    Set<String> allAttributes =
        ImmutableSet.copyOf(Iterables.concat(Iterables.transform(attributes, GET_VALUES)));
    boolean match = Iterables.any(constraint.getValues(), Predicates.in(allAttributes));
    return constraint.isNegated() ^ match;
  }

  /**
   * Tests whether an attribute matches a limit constraint.
   *
   * @param attributes Attributes to match against.
   * @param jobKey Key of the job with the limited constraint.
   * @param limit Limit value.
   * @param activeTasks All active tasks in the system.
   * @param attributeFetcher Interface for fetching attributes for hosts in the system.
   * @return {@code true} if the limit constraint is satisfied, {@code false} otherwise.
   */
  static boolean matches(final Set<Attribute> attributes,
      final JobKey jobKey,
      int limit,
      Iterable<ScheduledTask> activeTasks,
      final AttributeLoader attributeFetcher) {

    Predicate<ScheduledTask> sameJob =
        Predicates.compose(Predicates.equalTo(jobKey), Tasks.SCHEDULED_TO_JOB_KEY);

    Predicate<ScheduledTask> hasAttribute = new Predicate<ScheduledTask>() {
      @Override public boolean apply(ScheduledTask task) {
        Iterable<Attribute> hostAttributes =
            attributeFetcher.apply(task.getAssignedTask().getSlaveHost());
        return Iterables.any(hostAttributes, Predicates.in(attributes));
      }
    };

    return limit > Iterables.size(
        Iterables.filter(activeTasks, Predicates.and(sameJob, hasAttribute)));
  }
}
