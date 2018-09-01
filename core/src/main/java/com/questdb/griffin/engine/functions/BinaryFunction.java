/*******************************************************************************
 *    ___                  _   ____  ____
 *   / _ \ _   _  ___  ___| |_|  _ \| __ )
 *  | | | | | | |/ _ \/ __| __| | | |  _ \
 *  | |_| | |_| |  __/\__ \ |_| |_| | |_) |
 *   \__\_\\__,_|\___||___/\__|____/|____/
 *
 * Copyright (C) 2014-2018 Appsicle
 *
 * This program is free software: you can redistribute it and/or  modify
 * it under the terms of the GNU Affero General Public License, version 3,
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 ******************************************************************************/

package com.questdb.griffin.engine.functions;

import com.questdb.cairo.sql.Function;
import com.questdb.cairo.sql.RecordCursor;
import com.questdb.griffin.engine.functions.bind.BindVariableService;

public interface BinaryFunction extends Function {

    @Override
    default void close() {
        getLeft().close();
        getRight().close();
    }

    @Override
    default boolean isConstant() {
        return getLeft().isConstant() && getRight().isConstant();
    }

    @Override
    default void init(RecordCursor recordCursor, BindVariableService bindVariableService) {
        getLeft().init(recordCursor, bindVariableService);
        getRight().init(recordCursor, bindVariableService);
    }

    @Override
    default void toTop() {
        getLeft().toTop();
        getRight().toTop();
    }

    Function getLeft();

    Function getRight();
}