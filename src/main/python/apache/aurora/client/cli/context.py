#
# Copyright 2013 Apache Software Foundation
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

from __future__ import print_function
from collections import namedtuple
from fnmatch import fnmatch
import sys

from apache.aurora.common.clusters import CLUSTERS
from apache.aurora.common.aurora_job_key import AuroraJobKey
from apache.aurora.client.base import synthesize_url
from apache.aurora.client.cli import (
    Context,
    EXIT_NETWORK_ERROR,
    EXIT_INVALID_PARAMETER,
    EXIT_NETWORK_ERROR
)
from apache.aurora.client.config import get_config
from apache.aurora.client.factory import make_client
from twitter.common import log

from gen.apache.aurora.ttypes import ResponseCode


# Utility type, representing job keys with wildcards.
PartialJobKey = namedtuple('PartialJobKey', ['cluster', 'role', 'env', 'name'])


class AuroraCommandContext(Context):
  """A context object used by Aurora commands to manage command processing state
  and common operations.
  """

  def __init__(self):
    super(AuroraCommandContext, self).__init__()
    self.apis = {}

  def get_api(self, cluster):
    """Gets an API object for a specified cluster
    Keeps the API handle cached, so that only one handle for each cluster will be created in a
    session.
    """
    if cluster not in self.apis:
      api = make_client(cluster)
      self.apis[cluster] = api
    return self.apis[cluster]

  def get_job_config(self, jobkey, config_file):
    """Loads a job configuration from a config file."""
    jobname = jobkey.name
    return get_config(
      jobname,
      config_file,
      self.options.json,
      self.options.bindings,
      select_cluster=jobkey.cluster,
      select_role=jobkey.role,
      select_env=jobkey.env)

  def print_out(self, str):
    """Prints output. For debugging purposes, it's nice to be able to patch this
    and capture output.
    """
    print(str)

  def print_err(self, str):
    """Prints output to standard error."""
    print(str, file=sys.stderr)

  def open_page(self, url):
    import webbrowser
    webbrowser.open_new_tab(url)

  def open_job_page(self, api, jobkey):
    """Open the page for a job in the system web browser."""
    self.open_page(synthesize_url(api.scheduler.scheduler().url, jobkey.role,
        jobkey.env, jobkey.name))

  def check_and_log_response(self, resp):
    log.info('Response from scheduler: %s (message: %s)'
        % (ResponseCode._VALUES_TO_NAMES[resp.responseCode], resp.message))
    if resp.responseCode != ResponseCode.OK:
      raise self.CommandError(EXIT_NETWORK_ERROR, resp.message)

  @classmethod
  def parse_partial_jobkey(cls, key):
    """Given a partial jobkey, where parts can be wildcards, parse it.
    Slots that are wildcards will be replaced by "*".
    """
    parts = []
    for part in key.split('/'):
      parts.append(part)
    if len(parts) > 4:
      raise cls.CommandError(EXIT_INVALID_PARAMETER, 'Job key must have no more than 4 segments')
    while len(parts) < 4:
      parts.append('*')
    return PartialJobKey(*parts)

  def get_job_list(self, clusters, role=None):
    """Get a list of all jobs from a group of clusters.
    :param clusters: the clusters to query for jobs
    :param role: if specified, only return jobs for the role; otherwise, return all jobs.
    """
    result = []
    if '*' in role:
      role = None
    for cluster in clusters:
      api = self.get_api(cluster)
      resp = api.get_jobs(role)
      if resp.responseCode is not ResponseCode.OK:
        raise self.CommandError(EXIT_COMMAND_FAILURE, resp.message)
      result.extend([AuroraJobKey(cluster, job.key.role, job.key.environment, job.key.name)
          for job in resp.result.getJobsResult.configs])
    return result

  def get_jobs_matching_key(self, key):
    """Finds all jobs matching a key containing wildcard segments.
    This is potentially slow!
    TODO: insert a warning to users about slowness if the key contains wildcards!
    """

    def is_fully_bound(key):
      """Helper that checks if a key contains wildcards."""
      return not any('*' in component for component in [key.cluster, key.role, key.env, key.name])

    def filter_job_list(jobs, role, env, name):
      """Filter a list of jobs to get just the jobs that match the pattern from a key"""
      return [job for job in jobs if fnmatch(job.role, role) and fnmatch(job.env, env)
          and fnmatch(job.name, name)]

    # For cluster, we can expand the list of things we're looking for directly.
    # For other key elements, we need to just get a list of the jobs on the clusters, and filter
    # it for things that match.
    if key.cluster == '*':
      clusters_to_search = CLUSTERS
    else:
      clusters_to_search = [key.cluster]
    if is_fully_bound(key):
      return [AuroraJobKey(key.cluster, key.role, key.env, key.name)]
    else:
      jobs = filter_job_list(self.get_job_list(clusters_to_search, key.role),
          key.role, key.env, key.name)
      return jobs

  def get_job_status(self, key):
    api = self.get_api(key.cluster)
    resp = api.check_status(key)
    if resp.responseCode is not ResponseCode.OK:
      raise self.CommandError(EXIT_INVALID_PARAMETER, resp.message)
    return resp.result.scheduleStatusResult.tasks or None
