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
package com.dremio.service.sqlrunner.store;

import java.util.Optional;

import com.dremio.service.Service;
import com.dremio.service.sqlrunner.proto.SQLRunnerSessionProto.SQLRunnerSession;

/**
 * TransientStoreProvider for UserSession service
 */
public interface SQLRunnerSessionStore extends Service {

  /**
   * get SQL Runner session of specific user by userId
   *
   * @param userId
   * @return
   */
  Optional<SQLRunnerSession> get(String userId);

  /**
   * update user SQL Runner Session
   *
   * @param updatedSession
   * @return
   */
  SQLRunnerSession update(SQLRunnerSession updatedSession);

  /**
   * delete user SQL Runner Session
   *
   * @param userId
   * @return
   */
  void delete(String userId);

  int deleteExpired();
}
