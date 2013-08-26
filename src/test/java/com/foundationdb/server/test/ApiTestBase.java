/**
 * Copyright (C) 2009-2013 FoundationDB, LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.foundationdb.server.test;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertTrue;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Callable;

import com.foundationdb.ais.AISCloner;
import com.foundationdb.ais.model.*;
import com.foundationdb.ais.util.TableChangeValidator;
import com.foundationdb.qp.expression.BoundExpressions;
import com.foundationdb.qp.operator.QueryContext;
import com.foundationdb.qp.operator.SimpleQueryContext;
import com.foundationdb.qp.operator.StoreAdapter;
import com.foundationdb.qp.rowtype.Schema;
import com.foundationdb.sql.LayerInfoInterface;
import com.foundationdb.server.AkServerUtil;
import com.foundationdb.server.api.dml.scan.ScanFlag;
import com.foundationdb.server.rowdata.RowDef;
import com.foundationdb.server.rowdata.SchemaFactory;
import com.foundationdb.server.service.ServiceManagerImpl;
import com.foundationdb.server.service.config.ConfigurationService;
import com.foundationdb.server.rowdata.RowData;
import com.foundationdb.server.service.config.TestConfigService;
import com.foundationdb.server.service.dxl.DXLService;
import com.foundationdb.server.service.dxl.DXLTestHookRegistry;
import com.foundationdb.server.service.dxl.DXLTestHooks;
import com.foundationdb.server.service.lock.LockService;
import com.foundationdb.server.service.routines.RoutineLoader;
import com.foundationdb.server.service.security.SecurityService;
import com.foundationdb.server.service.servicemanager.GuicedServiceManager;
import com.foundationdb.server.service.transaction.TransactionService;
import com.foundationdb.server.service.tree.TreeService;
import com.foundationdb.server.t3expressions.T3RegistryService;
import com.foundationdb.server.t3expressions.TCastResolver;
import com.foundationdb.server.types3.pvalue.PValueSource;
import com.foundationdb.server.types3.pvalue.PValueSources;
import com.foundationdb.server.util.GroupIndexCreator;
import com.foundationdb.sql.StandardException;
import com.foundationdb.sql.aisddl.AlterTableDDL;
import com.foundationdb.sql.parser.AlterTableNode;
import com.foundationdb.sql.parser.SQLParser;
import com.foundationdb.sql.parser.StatementNode;
import com.foundationdb.util.AssertUtils;
import com.foundationdb.util.Exceptions;
import com.foundationdb.util.Strings;
import com.foundationdb.util.tap.TapReport;
import com.foundationdb.util.Undef;
import junit.framework.Assert;

import org.junit.After;
import org.junit.Before;

import com.foundationdb.server.api.dml.scan.RowDataOutput;
import com.foundationdb.server.store.Store;
import com.foundationdb.util.ListUtils;

import com.foundationdb.server.TableStatistics;
import com.foundationdb.server.api.DDLFunctions;
import com.foundationdb.server.api.DMLFunctions;
import com.foundationdb.server.api.dml.scan.CursorId;
import com.foundationdb.server.api.dml.scan.NewRow;
import com.foundationdb.server.api.dml.scan.NiceRow;
import com.foundationdb.server.api.dml.scan.RowOutput;
import com.foundationdb.server.api.dml.scan.ScanAllRequest;
import com.foundationdb.server.api.dml.scan.ScanRequest;
import com.foundationdb.server.error.InvalidOperationException;
import com.foundationdb.server.error.NoSuchTableException;
import com.foundationdb.server.service.ServiceManager;
import com.foundationdb.server.service.session.Session;
import org.junit.Rule;
import org.junit.rules.MethodRule;
import org.junit.rules.TestName;
import org.junit.rules.TestWatchman;
import org.junit.runners.model.FrameworkMethod;
import org.junit.runners.model.Statement;

/**
 * <p>Base class for all API tests. Contains a @SetUp that gives you a fresh DDLFunctions and DMLFunctions, plus
 * various convenience testing methods.</p>
 */
public class ApiTestBase {
    private static final int MIN_FREE_SPACE = 256 * 1024 * 1024;
    private static final String TAPS = System.getProperty("it.taps");
    protected final static Object UNDEF = Undef.only();

    private static final Comparator<? super TapReport> TAP_REPORT_COMPARATOR = new Comparator<TapReport>() {
        @Override
        public int compare(TapReport o1, TapReport o2) {
            return o1.getName().compareTo(o2.getName());
        }
    };

    public static interface TestRowOutput extends RowOutput {
        public int getRowCount();
        public void clear();
    }

    public static class ListRowOutput implements TestRowOutput {
        private final List<NewRow> rows = new ArrayList<>();
        private final List<NewRow> rowsUnmodifiable = Collections.unmodifiableList(rows);
        private int mark = 0;

        @Override
        public void output(NewRow row) {
            rows.add(row);
        }

        public List<NewRow> getRows() {
            return rowsUnmodifiable;
        }

        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public void clear() {
            rows.clear();
        }

        @Override
        public void mark() {
            mark = rows.size();
        }

        @Override
        public void rewind() {
            ListUtils.truncate(rows, mark);
        }
    }

    public static class CountingRowOutput implements TestRowOutput {
        private int count = 0;
        private int mark = 0;

        @Override
        public void output(NewRow row) {
            ++count;
        }

        @Override
        public void mark() {
            mark = count;
        }

        @Override
        public void rewind() {
            count = mark;
        }

        @Override
        public int getRowCount() {
            return count;
        }

        @Override
        public void clear() {
            count = 0;
        }
    }

    private static class RetryRule implements MethodRule {
        private static int MAX_TRIES = 5;
        private static int totalRetries = 0;

        @Override
        public Statement apply(final Statement base, FrameworkMethod method, Object target) {
            return new Statement() {
                @Override
                public void evaluate() throws Throwable {
                    int tryCount = 1;
                    try {
                        base.evaluate();
                    } catch(Throwable t) {
                        if(++tryCount > MAX_TRIES || !isRetryableException(t)) {
                            throw t;
                        }
                        ++totalRetries;
                    }
                }
            };
        }
    }


    protected ApiTestBase(String suffix)
    {
        final String name = this.getClass().getSimpleName();
        if (!name.endsWith(suffix)) {
            throw new RuntimeException(
                    String.format("You must rename %s to something like Foo%s", name, suffix)
            );
        }
    }

    private static ServiceManager sm;
    private Session session;
    private int aisGeneration;
    private final Set<RowUpdater> unfinishedRowUpdaters = new HashSet<>();
    private static Map<String,String> lastStartupConfigProperties = null;
    private static boolean needServicesRestart = false;
    protected static Set<Callable<Void>> beforeStopServices = new HashSet<>();

    @Rule
    public static final TestName testName = new TestName();

    @Rule
    public static final RetryRule retryRule = new RetryRule();


    protected String testName() {
        return testName.getMethodName();
    }

    @Before
    public final void startTestServices() throws Throwable {
        assertTrue("some row updaters were left over: " + unfinishedRowUpdaters, unfinishedRowUpdaters.isEmpty());
        System.setProperty("fdbsql.home", System.getProperty("user.home"));
        try {
            Map<String, String> startupConfigProperties = startupConfigProperties();
            Map<String,String> propertiesForEquality = propertiesForEquality(startupConfigProperties);
            if (needServicesRestart ||
                lastStartupConfigProperties == null ||
                !lastStartupConfigProperties.equals(propertiesForEquality))
            {
                // we need a shutdown if we needed a restart, or if the lastStartupConfigProperties are not null,
                // which (because of the condition on the "if" above) implies the last startup config properties
                // are different from this one's
                boolean needShutdown = needServicesRestart || lastStartupConfigProperties != null;
                if (needShutdown) {
                    needServicesRestart = false; // clear the flag if it was set
                    stopTestServices();
                }
                int attempt = 1;
                while (!AkServerUtil.cleanUpDirectory(TestConfigService.dataDirectory())) {
                    assertTrue("Too many directory failures", (attempt++ < 10));
                    TestConfigService.newDataDirectory();
                }
                assertNull("lastStartupConfigProperties should be null", lastStartupConfigProperties);
                sm = createServiceManager(startupConfigProperties);
                sm.startServices();
                ServiceManagerImpl.setServiceManager(sm);
                if (TAPS != null) {
                    sm.getStatisticsService().reset(TAPS);
                    sm.getStatisticsService().setEnabled(TAPS, true);
                }
                lastStartupConfigProperties = propertiesForEquality;
            }
            session = sm.getSessionService().createSession();
        } catch (Exception e) {
            handleStartupFailure(e);
        }
    }

    @Rule
    public MethodRule exceptionCatchingRule = new TestWatchman() {
        @Override
        public void failed(Throwable e, FrameworkMethod method)  {
            needServicesRestart = true;
        }
    };

    /**
     * Handle a failure during services startup. The default implementation is to just throw the exception, and
     * most tests should <em>not</em> override this. It's designed solely as a testing hook for FailureOnStartupIT.
     * @param e the startup exception
     * @throws Exception the startup exception
     */
    void handleStartupFailure(Exception e) throws Exception {
        if(sm != null && sm.getState() != ServiceManager.State.IDLE) {
            sm.stopServices();
        }
        throw e;
    }

    protected static boolean isRetryableException(Throwable t) {
        if(sm != null && sm.serviceIsStarted(Store.class)) {
            return sm.getStore().isRetryableException(t);
        }
        return false;
    }

    protected ServiceManager createServiceManager(Map<String, String> startupConfigProperties) {
        TestConfigService.setOverrides(startupConfigProperties);
        return new GuicedServiceManager(serviceBindingsProvider());
    }

    /** Specify special service bindings.
     * If you override this, you need to override {@link #startupConfigProperties} 
     * to return something different so that the special services aren't shared 
     * with other tests.
     */
    protected GuicedServiceManager.BindingsConfigurationProvider serviceBindingsProvider() {
        return GuicedServiceManager.testUrls();
    }

    @After
    public final void tearDownAllTables() throws Exception {
        if (lastStartupConfigProperties == null)
            return; // services never started up
        Set<RowUpdater> localUnfinishedUpdaters = new HashSet<>(unfinishedRowUpdaters);
        unfinishedRowUpdaters.clear();
        assertTrue("not all updaters were used: " + localUnfinishedUpdaters, localUnfinishedUpdaters.isEmpty());
        String openCursorsMessage = null;
        if (sm.serviceIsStarted(DXLService.class)) {
            dropAllTables();
            DXLTestHooks dxlTestHooks = DXLTestHookRegistry.get();
            // Check for any residual open cursors
            if (dxlTestHooks.openCursorsExist()) {
                openCursorsMessage = "open cursors remaining:" + dxlTestHooks.describeOpenCursors();
            }
        }
        if (TAPS != null) {
            TapReport[] reports = sm.getStatisticsService().getReport(TAPS);
            Arrays.sort(reports, TAP_REPORT_COMPARATOR);
            for (TapReport report : reports) {
                long totalNanos = report.getCumulativeTime();
                double totalSecs = ((double) totalNanos) / 1000000000.0d;
                double secsPer = totalSecs / report.getOutCount();
                System.err.printf("%s:\t in=%d out=%d time=%.2f sec (%d nanos, %.5f sec per out)%n",
                        report.getName(),
                        report.getInCount(),
                        report.getOutCount(),
                        totalSecs,
                        totalNanos,
                        secsPer
                );
            }
        }
        session.close();

        if (openCursorsMessage != null) {
            fail(openCursorsMessage);
        }
        
        needServicesRestart |= runningOutOfSpace();
    }
    
    private static boolean runningOutOfSpace() {
        return TestConfigService.dataDirectory().getFreeSpace() < MIN_FREE_SPACE;
    }

    private static void beforeStopServices() throws Exception {
        for (Callable<Void> callable : beforeStopServices) {
            callable.call();
        }
    }

    public final void stopTestServices() throws Exception {
        beforeStopServices();
        ServiceManagerImpl.setServiceManager(null);
        lastStartupConfigProperties = null;
        sm.stopServices();
    }
    
    public final void crashTestServices() throws Exception {
        beforeStopServices();
        sm.crashServices();
        sm = null;
        session = null;
        lastStartupConfigProperties = null;
    }
    
    public final void restartTestServices(Map<String, String> properties) throws Exception {
        ServiceManagerImpl.setServiceManager(null);
        sm = createServiceManager( properties );
        sm.startServices();
        session = sm.getSessionService().createSession();
        lastStartupConfigProperties = propertiesForEquality(properties);
        ddl(); // loads up the schema manager et al
        ServiceManagerImpl.setServiceManager(sm);
    }

    public final Session createNewSession()
    {
        return sm.getSessionService().createSession();
    }

    protected Map<String, String> defaultPropertiesToPreserveOnRestart() {
        return Collections.singletonMap(
                TestConfigService.DATA_PATH_KEY,
                TestConfigService.dataDirectory().getAbsolutePath());
    }

    protected boolean defaultDoCleanOnUnload() {
        return true;
    }

    public final void safeRestartTestServices() throws Exception {
        safeRestartTestServices(defaultPropertiesToPreserveOnRestart());
    }

    public final void safeRestartTestServices(Map<String, String> propertiesToPreserve) throws Exception {
        /*
         * Need this because deleting Trees currently is not transactional.  Therefore after
         * restart we recover the previous trees and forget about the deleteTree operations.
         * TODO: remove when transaction Tree management is done.
         */
        treeService().getDb().checkpoint();
        final boolean original = TestConfigService.getDoCleanOnUnload();
        try {
            TestConfigService.setDoCleanOnUnload(defaultDoCleanOnUnload());
            stopTestServices();
        } finally {
            TestConfigService.setDoCleanOnUnload(original);
        }
        restartTestServices(propertiesToPreserve);
    }
    
    protected final DMLFunctions dml() {
        return dxl().dmlFunctions();
    }

    protected final DDLFunctions ddl() {
        return dxl().ddlFunctions();
    }

    protected final Store store() {
        return sm.getStore();
    }

    protected final LayerInfoInterface layerInfo() {
        return sm.getLayerInfo();
    }

    protected String akibanFK(String childCol, String parentTable, String parentCol) {
        return String.format("GROUPING FOREIGN KEY (%s) REFERENCES \"%s\" (%s)",
                             childCol, parentTable, parentCol
        );
    }

    protected final Session session() {
        return session;
    }

    protected final StoreAdapter newStoreAdapter(Schema schema) {
        return newStoreAdapter(session(), schema);
    }

    protected final StoreAdapter newStoreAdapter(Session explicit_session, Schema schema) {
        return store().createAdapter(explicit_session, schema);
    }

    protected final QueryContext queryContext(StoreAdapter adapter) {
        return new SimpleQueryContext(adapter) {
                @Override
                public ServiceManager getServiceManager() {
                    return sm;
                }
            };
    }

    protected final AkibanInformationSchema ais() {
        return ddl().getAIS(session());
    }

    protected final ServiceManager serviceManager() {
        return sm;
    }

    protected final TCastResolver castResolver() {
        return sm.getServiceByClass(T3RegistryService.class).getCastsResolver();
    }

    protected final ConfigurationService configService() {
        return sm.getConfigurationService();
    }

    protected final DXLService dxl() {
        return sm.getDXL();
    }

    protected final TreeService treeService() {
        return sm.getServiceByClass(TreeService.class);
    }

    protected final TransactionService txnService() {
        return sm.getServiceByClass(TransactionService.class);
    }

    protected final LockService lockService() {
        return sm.getServiceByClass(LockService.class);
    }

    protected final RoutineLoader routineLoader() {
        return sm.getServiceByClass(RoutineLoader.class);
    }

    protected SecurityService securityService() {
        return sm.getServiceByClass(SecurityService.class);
    }

    protected final int aisGeneration() {
        return aisGeneration;
    }

    protected final void updateAISGeneration() {
        aisGeneration = ddl().getGenerationAsInt(session());
    }

    protected Map<String, String> startupConfigProperties() {
        return Collections.emptyMap();
    }

    // Property.equals() does not include the value.
    protected Map<String,String> propertiesForEquality(Map<String, String> properties) {
        Map<String,String> result = new HashMap<>(properties.size());
        for (Map.Entry<String, String> p : properties.entrySet()) {
            result.put(p.getKey(), p.getValue());
        }
        return result;
    }

    /**
     * A simple unique (per class) property that can be returned for tests
     * overriding the {@link #  } and/or
     * {@link #serviceBindingsProvider()} methods.
     */
    protected Map<String, String> uniqueStartupConfigProperties(Class clazz) {
        return Collections.singletonMap(
                "test.services",
                clazz.getName()
        );
    }

    protected AkibanInformationSchema createFromDDL(String schema, String ddl) {
        SchemaFactory schemaFactory = new SchemaFactory(schema);
        return schemaFactory.ais(ddl().getAIS(session()), ddl);
    }

    protected static final class SimpleColumn {
        public final String columnName;
        public final String typeName;
        public final Long param1;
        public final Long param2;

        public SimpleColumn(String columnName, String typeName) {
            this(columnName, typeName, null, null);
        }

        public SimpleColumn(String columnName, String typeName, Long param1, Long param2) {
            this.columnName = columnName;
            this.typeName = typeName;
            this.param1 = param1;
            this.param2 = param2;
        }
    }

    protected void runAlter(String schema, QueryContext queryContext, String sql) {
        SQLParser parser = new SQLParser();
        StatementNode node;
        try {
            node = parser.parseStatement(sql);
        } catch(StandardException e) {
            throw new RuntimeException(e);
        }
        org.junit.Assert.assertTrue("is alter node", node instanceof AlterTableNode);
        AlterTableDDL.alterTable(ddl(), dml(), session(), schema, (AlterTableNode) node, queryContext);
        updateAISGeneration();
    }

    protected final int createTableFromTypes(String schema, String table, boolean firstIsPk, boolean createIndexes,
                                             SimpleColumn... columns) {
        AISBuilder builder = new AISBuilder();
        builder.userTable(schema, table);

        int colPos = 0;
        SimpleColumn pk = firstIsPk ? columns[0] : new SimpleColumn("id", "int");
        builder.column(schema, table, pk.columnName, colPos++, pk.typeName, null, null, false, false, null, null);
        builder.index(schema, table, Index.PRIMARY_KEY_CONSTRAINT, true, Index.PRIMARY_KEY_CONSTRAINT);
        builder.indexColumn(schema, table, Index.PRIMARY_KEY_CONSTRAINT, pk.columnName, 0, true, null);

        for(int i = firstIsPk ? 1 : 0; i < columns.length; ++i) {
            SimpleColumn sc = columns[i];
            String name = sc.columnName == null ? "c" + (colPos + 1) : sc.columnName;
            builder.column(schema, table, name, colPos++, sc.typeName, sc.param1, sc.param2, true, false, null, null);

            if(createIndexes) {
                builder.index(schema, table, name, false, Index.KEY_CONSTRAINT);
                builder.indexColumn(schema, table, name, name, 0, true, null);
            }
        }

        UserTable tempTable = builder.akibanInformationSchema().getUserTable(schema, table);
        ddl().createTable(session(), tempTable);
        updateAISGeneration();
        return tableId(schema, table);
    }
    
    protected final int createTableFromTypes(String schema, String table, boolean firstIsPk, boolean createIndexes,
                                             String... typeNames) {
        SimpleColumn simpleColumns[] = new SimpleColumn[typeNames.length];
        for(int i = 0; i < typeNames.length; ++i) {
            simpleColumns[i] = new SimpleColumn(null, typeNames[i]);
        }
        return createTableFromTypes(schema, table, firstIsPk, createIndexes, simpleColumns);
    }

    protected final int createTable(String schema, String table, String definition) throws InvalidOperationException {
        String ddl = String.format("CREATE TABLE \"%s\" (%s)", table, definition);
        AkibanInformationSchema tempAIS = createFromDDL(schema, ddl);
        UserTable tempTable = tempAIS.getUserTable(schema, table);
        ddl().createTable(session(), tempTable);
        updateAISGeneration();
        return ddl().getTableId(session(), new TableName(schema, table));
    }

    protected final int createTable(String schema, String table, String... definitions) throws InvalidOperationException {
        assertTrue("must have at least one definition element", definitions.length >= 1);
        StringBuilder unifiedDef = new StringBuilder();
        for (String definition : definitions) {
            unifiedDef.append(definition).append(',');
        }
        unifiedDef.setLength(unifiedDef.length() - 1);
        return createTable(schema, table, unifiedDef.toString());
    }
    
    protected final void createSequence (String schema, String name, String definition) {
        String ddl = String.format("CREATE SEQUENCE %s %s", name, definition);
        AkibanInformationSchema tempAIS = createFromDDL(schema, ddl);
        Sequence sequence = tempAIS.getSequence(new TableName(schema, name));
        ddl().createSequence(session(), sequence);
        updateAISGeneration();
    }

    protected final void createView(String schema, String name, String definition) {
        String ddl = String.format("CREATE VIEW %s AS %s", name, definition);
        AkibanInformationSchema tempAIS = createFromDDL(schema, ddl);
        View view = tempAIS.getView(new TableName(schema, name));
        ddl().createView(session(), view);
        updateAISGeneration();
    }

    protected final int createTable(TableName tableName, String... definitions) throws InvalidOperationException {
        return createTable(tableName.getSchemaName(), tableName.getTableName(), definitions);
    }

    private AkibanInformationSchema createUniqueIndexInternal(String schema,
                                                              String table,
                                                              String indexName,
                                                              String... indexCols) {
        return createIndexInternal(schema, table, indexName, true, indexCols);
    }

    private AkibanInformationSchema createIndexInternal(String schema,
                                                        String table,
                                                        String indexName,
                                                        String... indexCols) {
        return createIndexInternal(schema, table, indexName, false, indexCols);
    }

    private AkibanInformationSchema createIndexInternal(String schema,
                                                        String table,
                                                        String indexName,
                                                        boolean unique,
                                                        String... indexCols) {
        String ddl = String.format("CREATE %s INDEX \"%s\" ON \"%s\".\"%s\"(%s)",
                                   unique ? "UNIQUE" : "",
                                   indexName,
                                   schema,
                                   table,
                                   Strings.join(Arrays.asList(indexCols), ","));
        return createFromDDL(schema, ddl);
    }

    protected final TableIndex createIndex(String schema, String table, String indexName, String... indexCols) {
        AkibanInformationSchema tempAIS = createIndexInternal(schema, table, indexName, indexCols);
        Index tempIndex = tempAIS.getUserTable(schema, table).getIndex(indexName);
        ddl().createIndexes(session(), Collections.singleton(tempIndex));
        updateAISGeneration();
        return ddl().getTable(session(), new TableName(schema, table)).getIndex(indexName);
    }

    protected final TableIndex createUniqueIndex(String schema, String table, String indexName, String... indexCols) {
        AkibanInformationSchema tempAIS = createUniqueIndexInternal(schema, table, indexName, indexCols);
        Index tempIndex = tempAIS.getUserTable(schema, table).getIndex(indexName);
        ddl().createIndexes(session(), Collections.singleton(tempIndex));
        updateAISGeneration();
        return ddl().getTable(session(), new TableName(schema, table)).getIndex(indexName);
    }

    protected final TableIndex createSpatialTableIndex(String schema, String table, String indexName,
                                                       int firstSpatialArgument, int dimensions,
                                                       String... indexCols) {
        AkibanInformationSchema tempAIS = AISCloner.clone(createIndexInternal(schema, table, indexName, indexCols));
        TableIndex tempIndex = tempAIS.getUserTable(schema, table).getIndex(indexName);
        tempIndex.markSpatial(firstSpatialArgument, dimensions);
        ddl().createIndexes(session(), Collections.singleton(tempIndex));
        updateAISGeneration();
        return ddl().getTable(session(), new TableName(schema, table)).getIndex(indexName);
    }

    /**
     * Add an Index to the given table that is marked as FOREIGN KEY. Intended
     * to be used by tests that need to simulate a table as created by the
     * adapter.
     */
    protected final TableIndex createGroupingFKIndex(String schema, String table, String indexName, String... indexCols) {
        assertTrue("grouping fk index must start with __akiban", indexName.startsWith("__akiban"));
        AkibanInformationSchema tempAIS = AISCloner.clone(createIndexInternal(schema, table, indexName, indexCols));
        UserTable userTable = tempAIS.getUserTable(schema, table);
        TableIndex tempIndex = userTable.getIndex(indexName);
        userTable.removeIndexes(Collections.singleton(tempIndex));
        TableIndex fkIndex = TableIndex.create(tempAIS, userTable, indexName, 0, false, "FOREIGN KEY");
        for(IndexColumn col : tempIndex.getKeyColumns()) {
            IndexColumn.create(fkIndex, col.getColumn(), col.getPosition(), col.isAscending(), col.getIndexedLength());
        }
        ddl().createIndexes(session(), Collections.singleton(fkIndex));
        updateAISGeneration();
        return ddl().getTable(session(), new TableName(schema, table)).getIndex(indexName);
    }

    protected final TableIndex createTableIndex(int tableId, String indexName, boolean unique, String... columns) {
        AkibanInformationSchema temp = AISCloner.clone(ais());
        return createTableIndex(temp.getUserTable(tableId), indexName, unique, columns);
    }
    
    protected final TableIndex createTableIndex(UserTable table, String indexName, boolean unique, String... columns) {
        TableIndex index = new TableIndex(table, indexName, 0, unique, "KEY");
        int pos = 0;
        for (String columnName : columns) {
            Column column = table.getColumn(columnName);
            IndexColumn.create(index, column, pos++, true, null);
        }
        ddl().createIndexes(session(), Collections.singleton(index));
        return getUserTable(table.getTableId()).getIndex(indexName);
    }

    /** @deprecated  **/
    protected final GroupIndex createGroupIndex(String groupName, String indexName, String tableColumnPairs) {
        return createGroupIndex(ais().getGroup(groupName).getName(), indexName, tableColumnPairs);
    }

    /** @deprecated  **/
    protected final GroupIndex createGroupIndex(String groupName, String indexName, String tableColumnPairs, Index.JoinType joinType) {
        return createGroupIndex(ais().getGroup(groupName).getName(), indexName, tableColumnPairs, joinType);
    }

    protected final GroupIndex createGroupIndex(TableName groupName, String indexName, String tableColumnPairs)
            throws InvalidOperationException {
        return createGroupIndex(groupName, indexName, tableColumnPairs, Index.JoinType.LEFT);
    }

    protected final GroupIndex createGroupIndex(TableName groupName, String indexName, String tableColumnPairs, Index.JoinType joinType)
            throws InvalidOperationException {
        AkibanInformationSchema ais = ddl().getAIS(session());
        final Index index;
        index = GroupIndexCreator.createIndex(ais, groupName, indexName, tableColumnPairs, joinType);
        ddl().createIndexes(session(), Collections.singleton(index));
        return ddl().getAIS(session()).getGroup(groupName).getIndex(indexName);
    }

    protected final GroupIndex createSpatialGroupIndex(TableName groupName,
                                                       String indexName,
                                                       int firstSpatialArgument, int dimensions,
                                                       String tableColumnPairs,
                                                       Index.JoinType joinType)
        throws InvalidOperationException {
        AkibanInformationSchema ais = ddl().getAIS(session());
        Index index = GroupIndexCreator.createIndex(ais, groupName, indexName, tableColumnPairs, joinType);
        index.markSpatial(firstSpatialArgument, dimensions);
        ddl().createIndexes(session(), Collections.singleton(index));
        return ddl().getAIS(session()).getGroup(groupName).getIndex(indexName);
    }

    protected final void deleteFullTextIndex(IndexName name)
    {
        ddl().dropTableIndexes(session(), name.getFullTableName(), Arrays.asList(name.getName()));
        updateAISGeneration();
    }
    
    protected final FullTextIndex createFullTextIndex(String schema, String table, String indexName, String... indexCols) {
        AkibanInformationSchema tempAIS = createIndexInternal(schema, table, indexName, "FULL_TEXT(" + Strings.join(Arrays.asList(indexCols), ",") + ")");
        Index tempIndex = tempAIS.getUserTable(schema, table).getFullTextIndex(indexName);
        ddl().createIndexes(session(), Collections.singleton(tempIndex));
        updateAISGeneration();
        return ddl().getUserTable(session(), new TableName(schema, table)).getFullTextIndex(indexName);
    }

    protected int createTablesAndIndexesFromDDL(String schema, String ddl) {
        SchemaFactory schemaFactory = new SchemaFactory(schema);
        
        // Insert DDL into the System. Returns the full system AIS.
        schemaFactory.ais(ddl(), session(), ddl);

        // Construct AIS again to get just newly created objects. Sort to find first root table of the user schema.
        AkibanInformationSchema ais = schemaFactory.ais(ddl);
        List<UserTable> tables = new ArrayList<>(ais.getUserTables().values());
        Collections.sort(tables, new Comparator<UserTable>() {
            @Override
            public int compare(UserTable t1, UserTable t2) {
                return t1.getTableId().compareTo(t2.getTableId());
            }
        });
        updateAISGeneration();
        return ddl().getTableId(session(), tables.get(0).getName());
    }

    protected int loadSchemaFile(String schemaName, File file) throws Exception {
        String sql = Strings.dumpFileToString(file);
        return createTablesAndIndexesFromDDL(schemaName, sql);
    }

    protected void loadDataFile(String schemaName, File file) throws Exception {
        String tableName = file.getName().replace(".dat", "");
        int tableId = tableId(schemaName, tableName);
        for (String line : Strings.dumpFile(file)) {
            String[] cols = line.split("\t");
            NewRow row = createNewRow(tableId);
            for (int i = 0; i < cols.length; i++)
                row.put(i, cols[i]);
            dml().writeRow(session(), row);
        }
    }

    /**
     * Expects an exact number of rows. This checks both the countRowExactly and countRowsApproximately
     * methods on DMLFunctions.
     * @param tableId the table to count
     * @param rowsExpected how many rows we expect
     * @throws InvalidOperationException for various reasons :)
     */
    protected final void expectRowCount(int tableId, long rowsExpected) throws InvalidOperationException {
        TableStatistics tableStats = dml().getTableStatistics(session(), tableId, true);
        assertEquals("table ID", tableId, tableStats.getRowDefId());
        assertEquals("rows by TableStatistics", rowsExpected, tableStats.getRowCount());
    }

    protected static RuntimeException unexpectedException(Throwable cause) {
        return new RuntimeException("unexpected exception", cause);
    }

    protected final List<RowData> scanFull(ScanRequest request) {
        try {
            return RowDataOutput.scanFull(session(), aisGeneration(), dml(), request);
        } catch (InvalidOperationException e) {
            throw new TestException(e);
        }
    }

    protected final List<NewRow> scanAll(ScanRequest request) throws InvalidOperationException {
        ListRowOutput output = new ListRowOutput();
        CursorId cursorId = dml().openCursor(session(), aisGeneration(), request);

        dml().scanSome(session(), cursorId, output);
        dml().closeCursor(session(), cursorId);

        return output.getRows();
    }

    protected final ScanRequest scanAllIndexRequest(TableIndex index)  throws InvalidOperationException {
        final Set<Integer> columns = new HashSet<>();
        for(IndexColumn icol : index.getKeyColumns()) {
            columns.add(icol.getColumn().getPosition());
        }
        return new ScanAllRequest(index.getTable().getTableId(), columns, index.getIndexId(), null);
    }

    protected final List<NewRow> scanAllIndex(TableIndex index)  throws InvalidOperationException {
        return scanAll(scanAllIndexRequest(index));
    }

    protected final void writeRow(int tableId, Object... values) {
        dml().writeRow(session(), createNewRow(tableId, values));
    }


    protected final RowUpdater update(NewRow oldRow) {
        RowUpdater updater = new RowUpdaterImpl(oldRow);
        unfinishedRowUpdaters.add(updater);
        return updater;
    }
    
    protected final RowUpdater update(int tableId, Object... values) {
        NewRow oldRow = createNewRow(tableId, values);
        return update(oldRow);
    }

    protected final int writeRows(NewRow... rows) throws InvalidOperationException {
        for (NewRow row : rows) {
            dml().writeRow(session(), row);
        }
        return rows.length;
    }

    protected final void deleteRow(int tableId, Object... values) {
        dml().deleteRow(session(), createNewRow(tableId, values), false);
    }

    protected final void expectRows(ScanRequest request, NewRow... expectedRows) throws InvalidOperationException {
        assertEquals("rows scanned", Arrays.asList(expectedRows), scanAll(request));
    }

    protected final ScanAllRequest scanAllRequest(int tableId) {
        return scanAllRequest(tableId, false);
    }

    protected final ScanAllRequest scanAllRequest(int tableId, boolean includingInternal) {
        Table uTable = ddl().getTable(session(), tableId);
        Set<Integer> allCols = new HashSet<>();
        int MAX = includingInternal ? uTable.getColumnsIncludingInternal().size() : uTable.getColumns().size();
        for (int i=0; i < MAX; ++i) {
            allCols.add(i);
        }
        return new ScanAllRequest(tableId, allCols);
    }

    protected final int indexId(String schema, String table, String index) {
        AkibanInformationSchema ais = ddl().getAIS(session());
        UserTable userTable = ais.getUserTable(schema, table);
        Index aisIndex = userTable.getIndex(index);
        if (aisIndex == null) {
            throw new RuntimeException("no such index: " + index);
        }
        return aisIndex.getIndexId();
    }

    protected final CursorId openFullScan(String schema, String table, String index) throws InvalidOperationException {
        AkibanInformationSchema ais = ddl().getAIS(session());
        UserTable userTable = ais.getUserTable(schema, table);
        Index aisIndex = userTable.getIndex(index);
        if (aisIndex == null) {
            throw new RuntimeException("no such index: " + index);
        }
        return openFullScan(
            userTable.getTableId(),
            aisIndex.getIndexId()
                           );
    }

    protected final CursorId openFullScan(int tableId, int indexId) throws InvalidOperationException {
        Table uTable = ddl().getTable(session(), tableId);
        Set<Integer> allCols = new HashSet<>();
        for (int i=0, MAX=uTable.getColumns().size(); i < MAX; ++i) {
            allCols.add(i);
        }
        ScanRequest request = new ScanAllRequest(tableId, allCols, indexId,
                EnumSet.of(ScanFlag.START_AT_BEGINNING, ScanFlag.END_AT_END)
        );
        return dml().openCursor(session(), aisGeneration(), request);
    }

    protected static Set<Integer> set(Integer... items) {
        return new HashSet<>(Arrays.asList(items));
    }

    @SafeVarargs
    protected static <T> T[] array(Class<T> ofClass, T... items) {
        if (ofClass == null) {
            throw new IllegalArgumentException(
                    "T[] of null class; you probably meant the array(Object...) overload "
                            +"with a null for the first element. Use array(Object.class, null, ...) instead"
            );
        }
        for(T t : items) {
            if(t != null && !ofClass.isInstance(t)) {
                throw new IllegalArgumentException("Mismatched class: " + t.getClass());
            }
        }
        return items;
    }

    protected static Object[] array(Object... items) {
        return array(Object.class, items);
    }

    protected static <T> T get(NewRow row, int field, Class<T> castAs) {
        Object obj = row.get(field);
        return castAs.cast(obj);
    }

    public static Object getObject(PValueSource pvalue) {
        if (pvalue.isNull())
            return null;
        if (pvalue.hasCacheValue())
            return pvalue.getObject();
        switch (PValueSources.pUnderlying(pvalue)) {
        case BOOL:
            return pvalue.getBoolean();
        case INT_8:
            return pvalue.getInt8();
        case INT_16:
            return pvalue.getInt16();
        case UINT_16:
            return pvalue.getUInt16();
        case INT_32:
            return pvalue.getInt32();
        case INT_64:
            return pvalue.getInt64();
        case FLOAT:
            return pvalue.getFloat();
        case DOUBLE:
            return pvalue.getDouble();
        case BYTES:
            return pvalue.getBytes();
        case STRING:
            return pvalue.getString();
        default:
            throw new AssertionError(pvalue);
        }
    }

    public static boolean isNull(BoundExpressions row, int pos) {
        return row.pvalue(pos).isNull();
    }

    public static Long getLong(BoundExpressions row, int field) {
        final Long result;
        PValueSource pvalue = row.pvalue(field);
        if (pvalue.isNull()) {
            result = null;
        }
        else {
            switch (PValueSources.pUnderlying(pvalue)) {
            case INT_8:
                result = (long) pvalue.getInt8();
                break;
            case INT_16:
                result = (long) pvalue.getInt16();
                break;
            case UINT_16:
                result = (long) pvalue.getUInt16();
                break;
            case INT_32:
                result = (long) pvalue.getInt32();
                break;
            case INT_64:
                result = pvalue.getInt64();
                break;
            default:
                throw new AssertionError(pvalue);
            }
        }
        return result;
    }

    protected final void expectFullRows(int tableId, NewRow... expectedRows) throws InvalidOperationException {
        ScanRequest all = scanAllRequest(tableId);
        expectRows(all, expectedRows);
        expectRowCount(tableId, expectedRows.length);
    }

    protected final List<NewRow> convertRowDatas(List<RowData> rowDatas) {
        List<NewRow> ret = new ArrayList<>(rowDatas.size());
        for(RowData rowData : rowDatas) {
            NewRow newRow = NiceRow.fromRowData(rowData, ddl().getRowDef(session(), rowData.getRowDefId()));
            ret.add(newRow);
        }
        return ret;
    }

    protected static Set<CursorId> cursorSet(CursorId... cursorIds) {
        Set<CursorId> set = new HashSet<>();
        for (CursorId id : cursorIds) {
            if(!set.add(id)) {
                fail(String.format("while adding %s to %s", id, set));
            }
        }
        return set;
    }

    public NewRow createNewRow(int tableId, Object... columns) {
        return createNewRow(getRowDef(tableId), columns);
    }
    
    public static NewRow createNewRow(RowDef rowDef, Object... columns) {
        NewRow row = new NiceRow(rowDef.getRowDefId(), rowDef);
        for (int i=0; i < columns.length; ++i) {
            if (columns[i] != UNDEF) {
                row.put(i, columns[i] );
            }
        }
        return row;
    }
    
    
    protected final void dropAllTables() throws InvalidOperationException {
        dropAllTables(session());
    }

    protected final void dropAllTables(Session session) throws InvalidOperationException {
        for(Routine routine : ddl().getAIS(session).getRoutines().values()) {
            TableName name = routine.getName();
            if (!name.getSchemaName().equals(TableName.SQLJ_SCHEMA) &&
                !name.getSchemaName().equals(TableName.SYS_SCHEMA) &&
                !name.getSchemaName().equals(TableName.SECURITY_SCHEMA)) {
                routineLoader().unloadRoutine(session(), name);
                ddl().dropRoutine(session(), name);
            }
        }
        for(SQLJJar jar : ddl().getAIS(session).getSQLJJars().values()) {
            ddl().dropSQLJJar(session(), jar.getName());
        }

        for(View view : ddl().getAIS(session).getViews().values()) {
            // In case one view references another, avoid having to delete in proper order.
            view.getTableColumnReferences().clear();
        }
        for(View view : ddl().getAIS(session).getViews().values()) {
            ddl().dropView(session, view.getName());
        }

        // Note: Group names, being derived, can change across DDL. Save root names instead.
        Set<TableName> groupRoots = new HashSet<>();
        for(UserTable table : ddl().getAIS(session).getUserTables().values()) {
            if(table.getParentJoin() == null && 
               !TableName.INFORMATION_SCHEMA.equals(table.getName().getSchemaName()) &&
               !TableName.SECURITY_SCHEMA.equals(table.getName().getSchemaName())) {
                groupRoots.add(table.getName());
            }
        }
        for(TableName rootName : groupRoots) {
            ddl().dropGroup(session, getUserTable(rootName).getGroup().getName());
        }

        for(Sequence s : ddl().getAIS(session).getSequences().values()) {
            String schema = s.getSequenceName().getSchemaName();
            if(!TableName.INFORMATION_SCHEMA.equals(schema) &&
               !TableName.SECURITY_SCHEMA.equals(schema) &&
               !TableName.SQLJ_SCHEMA.equals(schema) &&
               !TableName.SYS_SCHEMA.equals(schema)) {
                ddl().dropSequence(session, s.getSequenceName());
            }
        }

        // Now sanity check
        Set<TableName> uTables = new HashSet<>(ddl().getAIS(session).getUserTables().keySet());
        for (Iterator<TableName> iter = uTables.iterator(); iter.hasNext();) {
            String schemaName = iter.next().getSchemaName();
            if (TableName.INFORMATION_SCHEMA.equals(schemaName) ||
                TableName.SECURITY_SCHEMA.equals(schemaName)) {
                iter.remove();
            }
        }
        Assert.assertEquals("user table count", Collections.<TableName>emptySet(), uTables);

        Set<TableName> views = new HashSet<>(ddl().getAIS(session).getViews().keySet());
        Assert.assertEquals("user table count", Collections.<TableName>emptySet(), views);
    }

    protected static <T> void assertEqualLists(String message, List<? extends T> expected, List<? extends T> actual) {
        AssertUtils.assertCollectionEquals(message, expected, actual);
    }

    protected static class TestException extends RuntimeException {
        private final InvalidOperationException cause;

        public TestException(String message, InvalidOperationException cause) {
            super(message, cause);
            this.cause = cause;
        }

        public TestException(InvalidOperationException cause) {
            super(cause);
            this.cause = cause;
        }

        @Override
        public InvalidOperationException getCause() {
            assert super.getCause() == cause;
            return cause;
        }
    }

    protected final int tableId(String schema, String table) {
        return tableId(new TableName(schema, table));
    }

    protected final int tableId(TableName tableName) {
        try {
            return ddl().getTableId(session(), tableName);
        } catch (NoSuchTableException e) {
            throw new TestException(e);
        }
    }

    protected final TableName tableName(int tableId) {
        try {
            return ddl().getTableName(session(), tableId);
        } catch (NoSuchTableException e) {
            throw new TestException(e);
        }
    }

    protected final TableName tableName(String schema, String table) {
        return new TableName(schema, table);
    }

    protected final UserTable getUserTable(String schema, String name) {
        return getUserTable(tableName(schema, name));
    }

    protected final UserTable getUserTable(TableName name) {
        return ddl().getUserTable(session(), name);
    }

    protected final RowDef getRowDef(int rowDefId) {
        return getUserTable(rowDefId).rowDef();
    }

    protected final RowDef getRowDef(String schema, String table) {
        return getUserTable(schema, table).rowDef();
    }

    protected final RowDef getRowDef(TableName tableName) {
        return getUserTable(tableName).rowDef();
    }

    protected final UserTable getUserTable(int tableId) {
        final Table table;
        try {
            table = ddl().getTable(session(), tableId);
        } catch (NoSuchTableException e) {
            throw new TestException(e);
        }
        if (table.isUserTable()) {
            return (UserTable) table;
        }
        throw new RuntimeException("not a user table: " + table);
    }

    protected final Map<TableName,UserTable> getUserTables() {
        return stripAISTables(ddl().getAIS(session()).getUserTables());
    }

    private static <T extends Table> Map<TableName,T> stripAISTables(Map<TableName,T> map) {
        final Map<TableName,T> ret = new HashMap<>(map);
        for(Iterator<TableName> iter=ret.keySet().iterator(); iter.hasNext(); ) {
            if(TableName.INFORMATION_SCHEMA.equals(iter.next().getSchemaName())) {
                iter.remove();
            }
        }
        return ret;
    }

    protected void expectIndexes(int tableId, String... expectedIndexNames) {
        UserTable table = getUserTable(tableId);
        Set<String> expectedIndexesSet = new TreeSet<>(Arrays.asList(expectedIndexNames));
        Set<String> actualIndexes = new TreeSet<>();
        for (Index index : table.getIndexes()) {
            String indexName = index.getIndexName().getName();
            boolean added = actualIndexes.add(indexName);
            assertTrue("duplicate index name: " + indexName, added);
        }
        assertEquals("indexes in " + table.getName(), expectedIndexesSet, actualIndexes);
    }

    public interface RowUpdater {
        void to(Object... values);
        void to(NewRow newRow);
    }

    private class RowUpdaterImpl implements RowUpdater {
        @Override
        public void to(Object... values) {
            NewRow newRow = createNewRow(oldRow.getTableId(), values);
            to(newRow);
        }

        @Override
        public void to(NewRow newRow) {
            boolean removed = unfinishedRowUpdaters.remove(this);
            dml().updateRow(session(), oldRow, newRow, null);
            assertTrue("couldn't remove row updater " + toString(), removed);
        }

        @Override
        public String toString() {
            return "RowUpdater for " + oldRow;
        }

        private RowUpdaterImpl(NewRow oldRow) {
            this.oldRow = oldRow;
        }

        private final NewRow oldRow;
    }

    protected <T> T transactionally(Callable<T> callable) throws Exception {
        txnService().beginTransaction(session);
        try {
            T value = callable.call();
            txnService().commitTransaction(session);
            return value;
        }
        finally {
            txnService().rollbackTransactionIfOpen(session);
        }
    }
    
    protected <T> T transactionallyUnchecked(Callable<T> callable) {
        try {
            return transactionally(callable);
        } catch (Exception e) {
            throw Exceptions.throwAlways(e);
        }
    }
    
    protected void transactionallyUnchecked(final Runnable runnable) {
        transactionallyUnchecked(new Callable<Void>() {
            @Override
            public Void call() throws Exception {
                runnable.run();
                return null;
            }
        });
    }

    protected boolean pipelineMap() {
        return false;
    }

    protected DDLFunctions ddlForAlter() {
        return ddl();
    }

    protected void runAlter(TableChangeValidator.ChangeLevel expectedChangeLevel, String defaultSchema, String sql) {
        runAlter(session(), ddlForAlter(), dml(), null, expectedChangeLevel, defaultSchema, sql);
        updateAISGeneration();
    }

    protected static void runAlter(Session session, DDLFunctions ddl, DMLFunctions dml, QueryContext context,
                                   TableChangeValidator.ChangeLevel expectedChangeLevel, String defaultSchema, String sql) {
        SQLParser parser = new SQLParser();
        StatementNode node;
        try {
            node = parser.parseStatement(sql);
        } catch(StandardException e) {
            throw new RuntimeException(e);
        }
        assertTrue("is alter node", node instanceof AlterTableNode);
        TableChangeValidator.ChangeLevel level = AlterTableDDL.alterTable(ddl, dml, session, defaultSchema, (AlterTableNode) node, context);
        assertEquals("ChangeLevel", expectedChangeLevel, level);
    }
}
