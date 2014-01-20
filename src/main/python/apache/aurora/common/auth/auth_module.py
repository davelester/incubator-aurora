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

from abc import abstractmethod, abstractproperty

import getpass
import time

from twitter.common.lang import Interface

from gen.apache.aurora.ttypes import SessionKey


class AuthModule(Interface):
  @abstractproperty
  def mechanism(self):
    """Return the mechanism provided by this AuthModule."""

  @abstractmethod
  def payload(self):
    """Return the payload generated by the AuthModule."""

  def __call__(self):
    return SessionKey(mechanism=self.mechanism, data=self.payload())


class InsecureAuthModule(AuthModule):
  @property
  def mechanism(self):
    return 'UNAUTHENTICATED'

  def payload(self):
    return 'UNAUTHENTICATED'
