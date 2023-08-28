/*
 * SonarQube
 * Copyright (C) 2009-2023 SonarSource SA
 * mailto:info AT sonarsource DOT com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.sonar.server.platform.db.migration.version.v102;

import java.sql.SQLException;
import org.junit.Rule;
import org.junit.Test;
import org.sonar.db.CoreDbTester;
import org.sonar.server.platform.db.migration.step.DdlChange;

public class CreateIndexWebhookUuidCreatedAtInWebhookDeliveriesTest {

  public static final String TABLE_NAME = "webhook_deliveries";
  public static final String INDEX_NAME = "wd_webhook_uuid_created_at";

  @Rule
  public final CoreDbTester db = CoreDbTester.createForSchema(CreateIndexWebhookUuidCreatedAtInWebhookDeliveriesTest.class, "schema.sql");

  private final DdlChange createIndex = new CreateIndexWebhookUuidCreatedAtInWebhookDeliveries(db.database());

  @Test
  public void migration_should_create_index() throws SQLException {
    db.assertIndexDoesNotExist(TABLE_NAME, INDEX_NAME);

    createIndex.execute();

    db.assertIndex(TABLE_NAME, INDEX_NAME, "webhook_uuid", "created_at");
  }

  @Test
  public void migration_should_be_reentrant() throws SQLException {
    createIndex.execute();
    createIndex.execute();

    db.assertIndex(TABLE_NAME, INDEX_NAME, "webhook_uuid", "created_at");
  }

}
