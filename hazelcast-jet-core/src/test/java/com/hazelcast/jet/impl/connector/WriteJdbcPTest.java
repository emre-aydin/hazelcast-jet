/*
 * Copyright (c) 2008-2020, Hazelcast, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hazelcast.jet.impl.connector;

import com.hazelcast.function.BiConsumerEx;
import com.hazelcast.function.SupplierEx;
import com.hazelcast.jet.SimpleTestInClusterSupport;
import com.hazelcast.jet.pipeline.Pipeline;
import com.hazelcast.jet.pipeline.Sink;
import com.hazelcast.jet.pipeline.Sinks;
import com.hazelcast.jet.pipeline.test.TestSources;
import com.hazelcast.transaction.impl.xa.SerializableXID;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.postgresql.ds.common.BaseDataSource;
import org.postgresql.xa.PGXADataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import javax.sql.CommonDataSource;
import javax.sql.DataSource;
import javax.sql.XAConnection;
import javax.sql.XADataSource;
import javax.transaction.xa.XAException;
import javax.transaction.xa.XAResource;
import javax.transaction.xa.Xid;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLNonTransientException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map.Entry;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static com.hazelcast.jet.Util.entry;
import static javax.transaction.xa.XAResource.TMFAIL;
import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

public class WriteJdbcPTest extends SimpleTestInClusterSupport {

    @ClassRule
    @SuppressWarnings("rawtypes")
    public static PostgreSQLContainer container = new PostgreSQLContainer<>("postgres:12.1")
            .withCommand("postgres -c max_prepared_transactions=10");

    private static final int PERSON_COUNT = 10;

    private static AtomicInteger tableCounter = new AtomicInteger();
    private String tableName;

    @BeforeClass
    public static void setupClass() {
        initialize(2, null);
    }

    @Before
    public void setup() throws SQLException {
        tableName = "T" + tableCounter.incrementAndGet();
        logger.info("Will use table: " + tableName);
        try (Connection connection = ((DataSource) createDataSource(false)).getConnection()) {
            connection.createStatement()
                      .execute("CREATE TABLE " + tableName + "(id int, name varchar(255))");
        }
    }

    @Test
    public void test() throws SQLException {
        Pipeline p = Pipeline.create();
        p.readFrom(TestSources.items(IntStream.range(0, PERSON_COUNT).boxed().toArray(Integer[]::new)))
         .map(item -> entry(item, item.toString()))
         .writeTo(Sinks.jdbc("INSERT INTO " + tableName + " VALUES(?, ?)",
                 () -> createDataSource(false),
                 (stmt, item) -> {
                     stmt.setInt(1, item.getKey());
                     stmt.setString(2, item.getValue());
                 }
         ));

        instance().newJob(p).join();
        assertEquals(PERSON_COUNT, rowCount());
    }

    @Test
    public void testReconnect() throws SQLException {
        Pipeline p = Pipeline.create();
        p.readFrom(TestSources.items(IntStream.range(0, PERSON_COUNT).boxed().toArray(Integer[]::new)))
         .map(item -> entry(item, item.toString()))
         .writeTo(Sinks.jdbc("INSERT INTO " + tableName + " VALUES(?, ?)",
                 failTwiceDataSourceSupplier(), failOnceBindFn()
         ));

        instance().newJob(p).join();
        assertEquals(PERSON_COUNT, rowCount());
    }

    @Test(expected = CompletionException.class)
    public void testFailJob_withNonTransientException() throws SQLException {
        Pipeline p = Pipeline.create();
        p.readFrom(TestSources.items(IntStream.range(0, PERSON_COUNT).boxed().toArray(Integer[]::new)))
         .map(item -> entry(item, item.toString()))
         .writeTo(Sinks.jdbc("INSERT INTO " + tableName + " VALUES(?, ?)",
                 () -> createDataSource(false),
                 (stmt, item) -> {
                     throw new SQLNonTransientException();
                 }
         ));

        instance().newJob(p).join();
        assertEquals(PERSON_COUNT, rowCount());
    }

    @Test
    public void test2() throws Exception {
        XADataSource dataSource = (XADataSource) createDataSource(true);
        Xid xid1 = new SerializableXID(1, new byte[] {1}, new byte[1]);
        XAConnection conn = dataSource.getXAConnection();
        conn.getConnection().setAutoCommit(false);
        conn.getXAResource().start(xid1, XAResource.TMNOFLAGS);
        PreparedStatement stmt = conn.getConnection().prepareStatement("insert into " + tableName + " values (?, ?)");
        for (int i = 0; i < 10; i++) {
            stmt.setInt(1, i);
            stmt.setString(2, "name-" + i);
            stmt.addBatch();
        }
        stmt.executeBatch();
        try {
            conn.getXAResource().end(xid1, TMFAIL);
            conn.getXAResource().rollback(xid1);
            conn.getConnection().close();
        } catch (Exception e) {
            // log and ignore
            e.printStackTrace();
            conn = null;
            System.gc();
            System.gc();
            System.gc();
            System.runFinalization();
        }

        conn = dataSource.getXAConnection();
        conn.getConnection().setAutoCommit(false);
        try {
            conn.getXAResource().rollback(xid1);
        } catch (XAException e) {
            e.printStackTrace();
            if (e.errorCode != XAException.XAER_NOTA) {
                throw e;
            }
        }
        conn.getXAResource().start(xid1, XAResource.TMNOFLAGS);
    }

    @Test
    public void test1() throws Exception {
        XADataSource dataSource = (XADataSource) createDataSource(true);
        Xid xid1 = new SerializableXID(1, new byte[] {1}, new byte[1]);
        Xid xid2 = new SerializableXID(1, new byte[] {2}, new byte[1]);
        XAConnection conn = dataSource.getXAConnection();
        XAConnection conn2 = dataSource.getXAConnection();
        conn.getConnection().setAutoCommit(false);
        conn2.getConnection().setAutoCommit(false);
        try {
            conn.getXAResource().rollback(xid1);
        } catch (XAException e) {
            if (e.errorCode != XAException.XAER_NOTA) {
                throw e;
            }
        }
        try {
            conn2.getXAResource().rollback(xid1);
        } catch (XAException e) {
            if (e.errorCode != XAException.XAER_NOTA) {
                throw e;
            }
        }
        conn.getXAResource().start(xid1, XAResource.TMNOFLAGS);
        PreparedStatement stmt = conn.getConnection().prepareStatement("insert into " + tableName + " values (?, ?)");
        for (int i = 0; i < 10; i++) {
            stmt.setInt(1, i);
            stmt.setString(2, "name-" + i);
            stmt.addBatch();
        }
        stmt.executeBatch();
        conn.getConnection().close();
        conn2.getConnection().close();

        conn = dataSource.getXAConnection();
        conn2 = dataSource.getXAConnection();
        conn.getConnection().setAutoCommit(false);
        conn2.getConnection().setAutoCommit(false);
        try {
            conn.getXAResource().rollback(xid1);
        } catch (XAException e) {
            if (e.errorCode != XAException.XAER_NOTA) {
                throw e;
            }
        }
        try {
            conn2.getXAResource().rollback(xid1);
        } catch (XAException e) {
            if (e.errorCode != XAException.XAER_NOTA) {
                throw e;
            }
        }
        conn.getXAResource().start(xid1, XAResource.TMNOFLAGS);
        stmt = conn.getConnection().prepareStatement("insert into " + tableName + " values (?, ?)");
        for (int i = 0; i < 10; i++) {
            stmt.setInt(1, i);
            stmt.setString(2, "name-" + i);
            stmt.addBatch();
        }
        stmt.executeBatch();
    }

    @Test
    public void test_transactional_withRestarts_graceful_exOnce() throws Exception {
        test_transactional_withRestarts(true, true);
    }

    @Test
    public void test_transactional_withRestarts_forceful_exOnce() throws Exception {
        test_transactional_withRestarts(false, true);
    }

    @Test
    public void test_transactional_withRestarts_graceful_atLeastOnce() throws Exception {
        test_transactional_withRestarts(false, false);
    }

    @Test
    public void test_transactional_withRestarts_forceful_atLeastOnce() throws Exception {
        test_transactional_withRestarts(false, false);
    }

    private void test_transactional_withRestarts(boolean graceful, boolean exactlyOnce) throws Exception {
        Sink<Integer> sink = Sinks.<Integer>jdbcBuilder()
                                  .updateQuery("INSERT INTO " + tableName + " VALUES(?, ?)")
                                  .dataSourceSupplier(() -> createDataSource(true))
                                  .bindFn(
                                          (stmt, item) -> {
                                              stmt.setInt(1, item);
                                              stmt.setString(2, "name-" + item);
                                          })
                                  .exactlyOnce(exactlyOnce)
                                  .build();

        try (Connection conn = ((DataSource) createDataSource(false)).getConnection();
             PreparedStatement stmt = conn.prepareStatement("select id from " + tableName)
        ) {
            SinkStressTestUtil.test_withRestarts(instance(), logger, sink, graceful, exactlyOnce, () -> {
                ResultSet resultSet = stmt.executeQuery();
                List<Integer> actualRows = new ArrayList<>();
                while (resultSet.next()) {
                    actualRows.add(resultSet.getInt(1));
                }
                return actualRows;
            });
        }
    }

    private int rowCount() throws SQLException {
        try (Connection connection = ((DataSource) createDataSource(false)).getConnection();
             Statement statement = connection.createStatement()) {
            ResultSet resultSet = statement.executeQuery("SELECT COUNT(*) FROM " + tableName);
            if (!resultSet.next()) {
                return 0;
            }
            return resultSet.getInt(1);
        }
    }

    private static CommonDataSource createDataSource(boolean xa) {
        BaseDataSource dataSource = xa ? new PGXADataSource() : new PGSimpleDataSource();
        dataSource.setUrl(container.getJdbcUrl());
        dataSource.setUser(container.getUsername());
        dataSource.setPassword(container.getPassword());
        dataSource.setDatabaseName(container.getDatabaseName());

        return dataSource;
    }

    private static SupplierEx<DataSource> failTwiceDataSourceSupplier() {
        return new SupplierEx<DataSource>() {
            int remainingFailures = 2;

            @Override
            public DataSource getEx() throws SQLException {
                DataSource realDs = (DataSource) createDataSource(false);
                DataSource mockDs = mock(DataSource.class);
                doAnswer(invocation -> {
                    if (remainingFailures-- > 0) {
                        throw new SQLException("connection failure");
                    }
                    return realDs.getConnection();
                }).when(mockDs).getConnection();
                return mockDs;
            }
        };
    }

    private static BiConsumerEx<PreparedStatement, Entry<Integer, String>> failOnceBindFn() {
        return new BiConsumerEx<PreparedStatement, Entry<Integer, String>>() {
            int remainingFailures = 1;

            @Override
            public void acceptEx(PreparedStatement stmt, Entry<Integer, String> item) throws SQLException {
                if (remainingFailures-- > 0) {
                    throw new SQLException("bindFn failure");
                }
                stmt.setInt(1, item.getKey());
                stmt.setString(2, item.getValue());
            }
        };
    }
}
