/*
 * Copyright (C) 2017-2019 Dremio Corporation
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

type JobDetails = Record<string, any>;

const createJobStateEvents = () => {
  const subscribers = new Map<string, Set<Function>>();

  const notifySubscribers = (id: string, jobDetails: JobDetails) => {
    const currentSubscribers = subscribers.get(id);
    const wildcardSubscribers = subscribers.get("*");
    if (currentSubscribers) {
      currentSubscribers.forEach((cb) => cb(jobDetails));
    }
    if (wildcardSubscribers) {
      wildcardSubscribers.forEach((cb) => cb(jobDetails));
    }
  };

  const cleanUpSubscriber = (id: string, ref: Function) => {
    const currentSubscribers = subscribers.get(id);
    if (!currentSubscribers) {
      return;
    }
    currentSubscribers.delete(ref);
  };

  return {
    onJobStateChange: (id: string, cb: (jobDetails: JobDetails) => any) => {
      if (!subscribers.has(id)) {
        subscribers.set(id, new Set());
      }
      const currentSubscribers = subscribers.get(id);
      currentSubscribers!.add(cb);

      return () => {
        cleanUpSubscriber(id, cb);
      };
    },
    updateJobState: (id: string, jobDetails: JobDetails): void => {
      notifySubscribers(id, jobDetails);
    },
  };
};

const jobsStateEvents = createJobStateEvents();

export const getJobStateEvents = () => jobsStateEvents;
