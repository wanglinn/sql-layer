/**
 * Copyright (C) 2011 Akiban Technologies Inc.
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License, version 3,
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see http://www.gnu.org/licenses.
 */
package com.akiban.sql.optimizer;

import static com.akiban.qp.physicaloperator.API.valuesScan_Default;
import static com.akiban.qp.physicaloperator.API.project_Table;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.akiban.qp.exec.Plannable;
import com.akiban.qp.exec.UpdatePlannable;
import com.akiban.qp.expression.Expression;
import com.akiban.qp.physicaloperator.PhysicalOperator;
import com.akiban.qp.physicaloperator.UndefBindings;
import com.akiban.qp.rowtype.RowType;
import com.akiban.qp.rowtype.UserTableRowType;
import com.akiban.qp.rowtype.ValuesRowType;
import com.akiban.server.error.UnsupportedSQLException;
import com.akiban.server.service.instrumentation.SessionTracer;
import com.akiban.sql.optimizer.OperatorCompiler.Result;
import com.akiban.sql.optimizer.simplified.SimplifiedDeleteStatement;
import com.akiban.sql.optimizer.simplified.SimplifiedInsertStatement;
import com.akiban.sql.optimizer.simplified.SimplifiedTableStatement;
import com.akiban.sql.optimizer.simplified.SimplifiedUpdateStatement;
import com.akiban.sql.optimizer.simplified.SimplifiedQuery.SimpleExpression;
import com.akiban.sql.optimizer.simplified.SimplifiedQuery.TableNode;
import com.akiban.sql.optimizer.simplified.SimplifiedQuery.TableNodeOffsets;
import com.akiban.sql.optimizer.simplified.SimplifiedQuery.ColumnExpressionToIndex;
import com.akiban.sql.parser.DMLStatementNode;
import com.akiban.sql.parser.DeleteNode;
import com.akiban.sql.parser.InsertNode;
import com.akiban.sql.parser.NodeTypes;
import com.akiban.sql.parser.ParameterNode;
import com.akiban.sql.parser.UpdateNode;

/**
 * The CreateUpdateDelete operator compiler. This removes and refactors the 
 * code from the OperatorCompiler specifically for the Insert, Update, and Delete
 * operations. 
 * @author tjoneslo
 *
 */
public class CUDCompiler {
    private CUDCompiler () {
    }
    
    public static Result compileStatement (SessionTracer tracer, OperatorCompiler compiler, 
            DMLStatementNode stmtNode, List<ParameterNode> params) {
        
        SimplifiedTableStatement tableStmt = generateStatement(compiler, stmtNode);
        
        PhysicalOperator resultOper = resultsOperator (tracer, compiler, tableStmt, stmtNode, params);
        
        List<Expression> updateRow = generateExpressions (compiler, tableStmt);

        UserTableRowType targetRowType = compiler.tableRowType(tableStmt.getTargetTable());

        Plannable plan = generatePlan (stmtNode.getNodeType(), resultOper, updateRow, targetRowType);
        
        return new Result(plan, compiler.getParameterTypes(params));
    }
    

    private static SimplifiedTableStatement generateStatement (OperatorCompiler compiler, DMLStatementNode stmtNode) {
        SimplifiedTableStatement tableStatement;
        
        switch (stmtNode.getNodeType()) {
        case NodeTypes.UPDATE_NODE :
            tableStatement = new SimplifiedUpdateStatement ((UpdateNode)stmtNode, compiler.getJoinConditions());
            break;
        case NodeTypes.INSERT_NODE:
            tableStatement = new SimplifiedInsertStatement ((InsertNode)stmtNode, compiler.getJoinConditions());
            break;
        case NodeTypes.DELETE_NODE:
            tableStatement = new SimplifiedDeleteStatement ((DeleteNode)stmtNode, compiler.getJoinConditions());
            break;
        default:
            throw new UnsupportedSQLException (stmtNode.statementToString(), stmtNode);        
        }
        if (tableStatement.getJoins() != null)
            tableStatement.reorderJoins();
        return tableStatement;
    }
    
    private static Plannable generatePlan(int nodeType, PhysicalOperator resultOper,
            List<Expression> updateRow, RowType rowType) {
        UpdatePlannable plan = null;

        switch (nodeType) {
        case NodeTypes.UPDATE_NODE:
            // The operator generated by the compiler#selectCompiler()
            // has a project_default at the top, remove it, we want the whole table
            resultOper = resultOper.getInputOperators().get(0);
            plan = new com.akiban.qp.physicaloperator.Update_Default(resultOper,
                    new ExpressionRowUpdateFunction(updateRow, rowType));
            break;
        case NodeTypes.INSERT_NODE:
            // here we add a new project_table operator to re-project the
            // query results into a UserTableRowType for the insert table. 
            plan = new com.akiban.qp.physicaloperator.Insert_Default (
                    project_Table (resultOper, resultOper.rowType(), rowType, updateRow));
            break;
        case NodeTypes.DELETE_NODE:
            // Remove the project_default operator generated by the compiler#selectCompiler()
            // we want the whole table, which the SimplifiedDeleteNode has requested 
            resultOper = resultOper.getInputOperators().get(0);
            plan = new com.akiban.qp.physicaloperator.Delete_Default (resultOper);
            break;
        }
        return plan;
    }
    
    private static PhysicalOperator resultsOperator (SessionTracer tracer, OperatorCompiler compiler, SimplifiedTableStatement stmt,
            DMLStatementNode stmtNode, List<ParameterNode> params) {

        if (stmt.getValues() != null) {
            return values_Default (compiler, stmt);
        } else {
            Result result = compiler.compileSelect(tracer, stmt, stmtNode, params);
            return (PhysicalOperator)result.getResultOperator();
        }
    }

    private static PhysicalOperator values_Default(OperatorCompiler compiler, SimplifiedTableStatement stmt) {

        List<List<SimpleExpression>>values = stmt.getValues();
        Deque<ExpressionRow> exprRowList = new ArrayDeque<ExpressionRow>(values.size());
        
        // Using valuesRowType, not UserTableRowType here because values may not be in 
        // same number or order as user table columns. Re-order and fill will be done 
        // during execution. 
        ValuesRowType rowType = compiler.valuesRowType(values.get(0).size());
        for (List<SimpleExpression> row : values) {
            Expression[] expressions = new Expression[row.size()];
            int i = 0;
            for (SimpleExpression expr : row) {
                expressions[i] = expr.generateExpression(stmt.getFieldOffset());
                i++;
            }
            exprRowList.add(new ExpressionRow(rowType, UndefBindings.only(), expressions));
        }
        return valuesScan_Default (exprRowList, rowType);
        
    }

    private static List<Expression> generateExpressions(OperatorCompiler compiler, SimplifiedTableStatement stmt) {
        UserTableRowType targetRowType = compiler.tableRowType(stmt.getTargetTable());
        
        Map<TableNode,Integer> tableOffsets = new HashMap<TableNode,Integer>(1);
        tableOffsets.put(stmt.getTargetTable(), 0);
        
        ColumnExpressionToIndex fieldOffsets = new TableNodeOffsets(tableOffsets);
        if (stmt.getFieldOffset() != null) {
            fieldOffsets = stmt.getFieldOffset();
        }
       
        Expression[] updates = new Expression[targetRowType.nFields()];
        for (SimplifiedTableStatement.TargetColumn targetColumn : 
                 stmt.getTargetColumns()) {
            updates[targetColumn.getColumn().getPosition()] =
                targetColumn.getValue().generateExpression(fieldOffsets);
        }
        return Arrays.asList(updates);
    }
    
}
