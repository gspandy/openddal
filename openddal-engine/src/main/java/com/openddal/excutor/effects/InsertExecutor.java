/*
 * Copyright 2014-2015 the original author or authors
 *
 * Licensed under the Apache License, Version 2.0 (the “License”);
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an “AS IS” BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.openddal.excutor.effects;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.openddal.command.Prepared;
import com.openddal.command.dml.Insert;
import com.openddal.command.dml.Query;
import com.openddal.command.expression.Expression;
import com.openddal.dbobject.table.Column;
import com.openddal.dbobject.table.TableMate;
import com.openddal.excutor.ExecutionFramework;
import com.openddal.excutor.works.UpdateWorker;
import com.openddal.message.DbException;
import com.openddal.repo.works.JdbcWorker;
import com.openddal.result.ResultInterface;
import com.openddal.result.ResultTarget;
import com.openddal.result.Row;
import com.openddal.result.SearchRow;
import com.openddal.route.rule.ObjectNode;
import com.openddal.route.rule.RoutingResult;
import com.openddal.util.New;
import com.openddal.util.StatementBuilder;
import com.openddal.value.Value;

/**
 * @author <a href="mailto:jorgie.mail@gmail.com">jorgie li</a>
 *         TODO validation rule column
 */
public class InsertExecutor extends ExecutionFramework<Insert> implements ResultTarget {

    private int rowNumber;
    private int affectRows;
    private List<Row> newRows = New.arrayList(10);
    private List<UpdateWorker> workers;

    

    /**
     * @param prepared
     */
    public InsertExecutor(Insert prepared) {
        super(prepared);
    }
    
    @Override
    protected void doPrepare() {
        TableMate table = toTableMate(prepared.getTable());
        table.check();
        prepared.setCurrentRowNumber(0);
        rowNumber = 0;
        affectRows = 0;
        ArrayList<Expression[]> list = prepared.getList();
        Column[] columns = prepared.getColumns();
        int listSize = list.size();
        if (listSize > 0) {
            int columnLen = columns.length;
            for (int x = 0; x < listSize; x++) {
                Row newRow = table.getTemplateRow();
                Expression[] expr = list.get(x);
                prepared.setCurrentRowNumber(x + 1);
                for (int i = 0; i < columnLen; i++) {
                    Column c = columns[i];
                    int index = c.getColumnId();
                    Expression e = expr[i];
                    if (e != null) {
                        // e can be null (DEFAULT)
                        e = e.optimize(session);
                        try {
                            Value v = c.convert(e.getValue(session));
                            newRow.setValue(index, v);
                        } catch (DbException ex) {
                            throw prepared.setRow(ex, x, Prepared.getSQL(expr));
                        }
                    }
                }
                rowNumber++;
                addNewRow(newRow);
            }
        } else {
            Query query = prepared.getQuery();
            if (prepared.isInsertFromSelect()) {
                query.query(0, this);
            } else {
                ResultInterface rows = query.query(0);
                while (rows.next()) {
                    Value[] r = rows.currentRow();
                    addRow(r);
                }
                rows.close();
            }
        }
    }

    @Override
    public int doUpdate() {
        
    }
    
    
    protected int updateRows(TableMate table, List<Row> rows) {
        Map<BatchKey, List<List<Value>>> batches = New.hashMap();
        session.checkCanceled();
        for (Row row : rows) {
            RoutingResult result = routingHandler.doRoute(table, row);
            ObjectNode[] selectNodes = result.getSelectNodes();
            workers = New.arrayList(selectNodes.length);
            for (ObjectNode objectNode : selectNodes) {
                UpdateWorker worker = queryHandlerFactory.createUpdateWorker(prepared, objectNode, row);
                workers.add(worker);
            }
        }
        if(workers.size() > 5) {
            queryHandlerFactory.mergeToBatchUpdateWorker(session, workers);
        }
        try {
            addRuningJdbcWorkers(workers);
            int affectRows = 0;
            if (workers.size() > 1) {
                int queryTimeout = getQueryTimeout();//MILLISECONDS
                List<Future<Integer[]>> invokeAll;
                if (queryTimeout > 0) {
                    invokeAll = jdbcExecutor.invokeAll(workers, queryTimeout, TimeUnit.MILLISECONDS);
                } else {
                    invokeAll = jdbcExecutor.invokeAll(workers);
                }
                for (Future<Integer[]> future : invokeAll) {
                    Integer[] integers = future.get();
                    for (Integer integer : integers) {
                        affectRows += integer;
                    }
                }
            } else if (workers.size() == 1) {
                Integer[] integers = workers.get(0).doWork();
                for (Integer integer : integers) {
                    affectRows += integer;
                }
            }
            return affectRows;
        } catch (InterruptedException e) {
            throw DbException.convert(e);
        } catch (ExecutionException e) {
            throw DbException.convert(e.getCause());
        } finally {
            removeRuningJdbcWorkers(workers);
            for (JdbcWorker<Integer[]> jdbcWorker : workers) {
                jdbcWorker.closeResource();
            }
        }
    }



    @Override
    public void addRow(Value[] values) {
        TableMate table = toTableMate(prepared.getTable());
        Row newRow = table.getTemplateRow();
        Column[] columns = prepared.getColumns();
        prepared.setCurrentRowNumber(++rowNumber);
        for (int j = 0, len = columns.length; j < len; j++) {
            Column c = columns[j];
            int index = c.getColumnId();
            try {
                Value v = c.convert(values[j]);
                newRow.setValue(index, v);
            } catch (DbException ex) {
                throw prepared.setRow(ex, rowNumber, Prepared.getSQL(values));
            }
        }
        addNewRow(newRow);
    }

    @Override
    public int getRowCount() {
        return rowNumber;
    }

    private void addNewRow(Row newRow) {
        newRows.add(newRow);
    }
    
    private void addNewRowFlushIfNeed(Row newRow) {
        newRows.add(newRow);
        if (newRows.size() >= 500) {
            flushNewRows();
        }
    }

    private void flushNewRows() {
        try {
            TableMate table = castTableMate(prepared.getTable());
            if (newRows.isEmpty()) {
                return;
            } else if (newRows.size() == 1) {
                affectRows += updateRow(table, newRows.get(0));
            } else {
                affectRows += updateRows(table, newRows);
            }
        } finally {
            newRows.clear();
        }

    }

    @Override
    protected List<Value> doTranslate(TableNode node, SearchRow row, StatementBuilder buff) {
        String forTable = node.getCompositeObjectName();
        TableMate table = castTableMate(prepared.getTable());
        Column[] columns = table.getColumns();
        return buildInsert(forTable, columns, row, buff);
    }


    @Override
    protected String doExplain() {
        // TODO Auto-generated method stub
        return null;
    }
    
    
    private static class BatchKey implements Serializable {
        private static final long serialVersionUID = 1L;

        private final String shardName;
        private final String sql;

        /**
         * @param shardName
         * @param sql
         */
        private BatchKey(String shardName, String sql) {
            super();
            this.shardName = shardName;
            this.sql = sql;
        }

        @Override
        public int hashCode() {
            final int prime = 31;
            int result = 1;
            result = prime * result + ((shardName == null) ? 0 : shardName.hashCode());
            result = prime * result + ((sql == null) ? 0 : sql.hashCode());
            return result;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj)
                return true;
            if (obj == null)
                return false;
            if (getClass() != obj.getClass())
                return false;
            BatchKey other = (BatchKey) obj;
            if (shardName == null) {
                if (other.shardName != null)
                    return false;
            } else if (!shardName.equals(other.shardName))
                return false;
            if (sql == null) {
                if (other.sql != null)
                    return false;
            } else if (!sql.equals(other.sql))
                return false;
            return true;
        }

        @Override
        public String toString() {
            return "BatchKey [shardName=" + shardName + ", sql=" + sql + "]";
        }
    }

}