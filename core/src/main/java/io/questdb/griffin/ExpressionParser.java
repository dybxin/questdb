/*******************************************************************************
 *     ___                  _   ____  ____
 *    / _ \ _   _  ___  ___| |_|  _ \| __ )
 *   | | | | | | |/ _ \/ __| __| | | |  _ \
 *   | |_| | |_| |  __/\__ \ |_| |_| | |_) |
 *    \__\_\\__,_|\___||___/\__|____/|____/
 *
 *  Copyright (c) 2014-2019 Appsicle
 *  Copyright (c) 2019-2020 QuestDB
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 ******************************************************************************/

package io.questdb.griffin;

import io.questdb.cairo.ColumnType;
import io.questdb.griffin.model.ExpressionNode;
import io.questdb.std.*;

class ExpressionParser {

    private static final IntHashSet nonLiteralBranches = new IntHashSet();
    private static final int BRANCH_NONE = 0;
    private static final int BRANCH_COMMA = 1;
    private static final int BRANCH_LEFT_BRACE = 2;
    private static final int BRANCH_RIGHT_BRACE = 3;
    private static final int BRANCH_CONSTANT = 4;
    private static final int BRANCH_OPERATOR = 5;
    private static final int BRANCH_LITERAL = 6;
    private static final int BRANCH_LAMBDA = 7;
    private static final int BRANCH_CASE_START = 9;
    private static final int BRANCH_CASE_CONTROL = 10;
    private static final int BRANCH_CAST_AS = 11;
    private static final int BRANCH_DOT = 12;
    private static final LowerCaseAsciiCharSequenceIntHashMap caseKeywords = new LowerCaseAsciiCharSequenceIntHashMap();
    private final ObjStack<ExpressionNode> opStack = new ObjStack<>();
    private final IntStack paramCountStack = new IntStack();
    private final IntStack argStackDepthStack = new IntStack();
    private final IntStack castBraceCountStack = new IntStack();
    private final IntStack caseBraceCountStack = new IntStack();
    private final IntStack backupParamCountStack = new IntStack();
    private final IntStack backupArgStackDepthStack = new IntStack();
    private final IntStack backupCastBraceCountStack = new IntStack();
    private final IntStack backupCaseBraceCountStack = new IntStack();
    private final ObjectPool<ExpressionNode> expressionNodePool;
    private final SqlParser sqlParser;
    private final CharacterStore characterStore;

    ExpressionParser(
            ObjectPool<ExpressionNode> expressionNodePool,
            SqlParser sqlParser,
            CharacterStore characterStore
    ) {
        this.expressionNodePool = expressionNodePool;
        this.sqlParser = sqlParser;
        this.characterStore = characterStore;
    }

    private static SqlException missingArgs(int position) {
        return SqlException.$(position, "missing arguments");
    }

    private boolean isCount() {
        return opStack.size() == 2 && Chars.equals(opStack.peek().token, '(') && Chars.equals("count", opStack.peek(1).token);
    }

    private int onNode(ExpressionParserListener listener, ExpressionNode node, int argStackDepth) throws SqlException {
        if (argStackDepth < node.paramCount) {
            throw SqlException.position(node.position).put("too few arguments for '").put(node.token).put("' [found=").put(argStackDepth).put(",expected=").put(node.paramCount).put(']');
        }
        listener.onNode(node);
        return argStackDepth - node.paramCount + 1;
    }

    @SuppressWarnings("ConstantConditions")
    void parseExpr(GenericLexer lexer, ExpressionParserListener listener) throws SqlException {
        try {
            int paramCount = 0;
            int braceCount = 0;
            int caseCount = 0;
            int argStackDepth = 0;
            int castAsCount = 0;

            ExpressionNode node;
            CharSequence tok;
            char thisChar;
            int prevBranch;
            int thisBranch = BRANCH_NONE;

            OUT:
            while ((tok = SqlUtil.fetchNext(lexer)) != null) {
                thisChar = tok.charAt(0);
                prevBranch = thisBranch;
                boolean processDefaultBranch = false;
                switch (thisChar) {
                    case '.':
                        // Check what is on stack. If we have 'a .b' we have to stop processing
                        if (thisBranch == BRANCH_LITERAL || thisBranch == BRANCH_CONSTANT) {
                            final int p = lexer.lastTokenPosition();
                            char c = lexer.getContent().charAt(p - 1);
                            if (GenericLexer.WHITESPACE_CH.contains(c)) {
                                lexer.unparse();
                                break OUT;
                            }

                            if (Chars.isQuote(c)) {
                                ExpressionNode en = opStack.pop();
                                CharacterStoreEntry cse = characterStore.newEntry();
                                cse.put(GenericLexer.unquote(en.token)).put('.');
                                opStack.push(expressionNodePool.next().of(ExpressionNode.LITERAL, cse.toImmutable(), Integer.MIN_VALUE, en.position));
                            } else {
                                // attach dot to existing literal or constant
                                ExpressionNode en = opStack.peek();
                                ((GenericLexer.FloatingSequence) en.token).setHi(p + 1);
                            }
                        }
                        if (prevBranch == BRANCH_DOT) {
                            throw SqlException.$(lexer.lastTokenPosition(), "too many dots");
                        }
                        thisBranch = BRANCH_DOT;
                        break;
                    case ',':
                        if (prevBranch == BRANCH_COMMA || prevBranch == BRANCH_LEFT_BRACE) {
                            throw missingArgs(lexer.lastTokenPosition());
                        }
                        thisBranch = BRANCH_COMMA;

                        if (braceCount == 0) {
                            // comma outside of braces
                            lexer.unparse();
                            break OUT;
                        }

                        if (castBraceCountStack.peek() == braceCount) {
                            throw SqlException.$(lexer.lastTokenPosition(), "',' is not expected here");
                        }

                        // If the token is a function argument separator (e.g., a comma):
                        // Until the token at the top of the stack is a left parenthesis,
                        // pop operators off the stack onto the output queue. If no left
                        // parentheses are encountered, either the separator was misplaced or
                        // parentheses were mismatched.
                        while ((node = opStack.pop()) != null && node.token.charAt(0) != '(') {
                            argStackDepth = onNode(listener, node, argStackDepth);
                        }

                        if (node != null) {
                            opStack.push(node);
                        }

                        paramCount++;
                        break;

                    case '(':
                        thisBranch = BRANCH_LEFT_BRACE;
                        // If the token is a left parenthesis, then push it onto the stack.
                        paramCountStack.push(paramCount);
                        paramCount = 0;

                        argStackDepthStack.push(argStackDepth);
                        argStackDepth = 0;

                        // precedence must be max value to make sure control node isn't
                        // consumed as parameter to a greedy function
                        opStack.push(expressionNodePool.next().of(ExpressionNode.CONTROL, "(", Integer.MAX_VALUE, lexer.lastTokenPosition()));

                        // check if this brace was opened after 'cast'
                        if (castBraceCountStack.size() > 0 && castBraceCountStack.peek() == -1) {
                            castBraceCountStack.update(braceCount + 1);
                        }
                        braceCount++;
                        break;

                    case ')':
                        if (prevBranch == BRANCH_COMMA) {
                            throw missingArgs(lexer.lastTokenPosition());
                        }

                        if (braceCount == 0) {
                            lexer.unparse();
                            break OUT;
                        }

                        thisBranch = BRANCH_RIGHT_BRACE;
                        final int localParamCount = (prevBranch == BRANCH_LEFT_BRACE ? 0 : paramCount + 1);
                        final boolean thisWasCast;

                        if (castBraceCountStack.size() > 0 && castBraceCountStack.peek() == braceCount) {
                            if (castAsCount == 0) {
                                throw SqlException.$(lexer.lastTokenPosition(), "'as' missing");
                            }

                            castAsCount--;
                            castBraceCountStack.pop();
                            thisWasCast = true;
                        } else {
                            thisWasCast = false;
                        }

                        braceCount--;
                        // If the token is a right parenthesis:
                        // Until the token at the top of the stack is a left parenthesis, pop operators off the stack onto the output queue.
                        // Pop the left parenthesis from the stack, but not onto the output queue.
                        //        If the token at the top of the stack is a function token, pop it onto the output queue.
                        //        If the stack runs out without finding a left parenthesis, then there are mismatched parentheses.
                        while ((node = opStack.pop()) != null && node.token.charAt(0) != '(') {
                            // special case - (*) expression
                            if (Chars.equals(node.token, '*') && argStackDepth == 0 && isCount()) {
                                argStackDepth = onNode(listener, node, 2);
                            } else {
                                if (thisWasCast) {
                                    // validate type
                                    final int columnType = ColumnType.columnTypeOf(node.token);

                                    if (columnType < 0 || columnType > ColumnType.LONG256) {
                                        throw SqlException.$(node.position, "invalid type");
                                    }

                                    node.type = ExpressionNode.CONSTANT;
                                }
                                argStackDepth = onNode(listener, node, argStackDepth);
                            }
                        }

                        if (argStackDepthStack.notEmpty()) {
                            argStackDepth += argStackDepthStack.pop();
                        }

                        // enable operation or literal absorb parameters
                        if ((node = opStack.peek()) != null && (node.type == ExpressionNode.LITERAL || (node.type == ExpressionNode.SET_OPERATION))) {
                            node.paramCount = localParamCount + (node.paramCount == 2 ? 1 : 0);
                            node.type = ExpressionNode.FUNCTION;
                            argStackDepth = onNode(listener, node, argStackDepth);
                            opStack.pop();
                        } else {
                            // not at function?
                            // peek the op stack to make sure it isn't a repeating brace
                            if (localParamCount > 1
                                    && (node = opStack.peek()) != null
                                    && node.token.charAt(0) == '('
                            ) {
                                throw SqlException.$(lexer.lastTokenPosition(), "no function or operator?");
                            }
                        }

                        if (paramCountStack.notEmpty()) {
                            paramCount = paramCountStack.pop();
                        }

                        break;
                    case 'c':
                    case 'C':
                        if (SqlKeywords.isCastKeyword(tok)) {
                            castBraceCountStack.push(-1);
                            thisBranch = BRANCH_OPERATOR;
                            opStack.push(expressionNodePool.next().of(ExpressionNode.LITERAL, "cast", Integer.MIN_VALUE, lexer.lastTokenPosition()));
                            break;
                        }
                        processDefaultBranch = true;
                        break;
                    case 'a':
                    case 'A':
                        if (SqlKeywords.isAsKeyword(tok)) {
                            if (castAsCount < castBraceCountStack.size()) {

                                thisBranch = BRANCH_CAST_AS;

                                // push existing args to the listener
                                while ((node = opStack.pop()) != null && node.token.charAt(0) != '(') {
                                    argStackDepth = onNode(listener, node, argStackDepth);
                                }

                                if (node != null) {
                                    opStack.push(node);
                                }

                                paramCount++;
                                castAsCount++;
                            } else {
                                processDefaultBranch = true;
                            }
                        } else {
                            processDefaultBranch = true;
                        }
                        break;
                    case 's':
                    case 'S':
                        if (SqlKeywords.isSelectKeyword(tok)) {
                            thisBranch = BRANCH_LAMBDA;
                            argStackDepth = processLambdaQuery(lexer, listener, argStackDepth);
                        } else {
                            processDefaultBranch = true;
                        }
                        break;
                    case '0':
                    case '1':
                    case '2':
                    case '3':
                    case '4':
                    case '5':
                    case '6':
                    case '7':
                    case '8':
                    case '9':
                    case '\'':
                        thisBranch = BRANCH_CONSTANT;
                        if (prevBranch == BRANCH_DOT) {
                            final ExpressionNode en = opStack.peek();
                            if (en != null && en.type != ExpressionNode.CONTROL) {
                                // check if this is '1.2' or '1. 2'
                                if (lexer.lastTokenPosition() > 0 && lexer.getContent().charAt(lexer.lastTokenPosition() - 1) == '.') {
                                    if (en.token instanceof GenericLexer.FloatingSequence) {
                                        ((GenericLexer.FloatingSequence) en.token).setHi(lexer.getTokenHi());
                                    } else {
                                        opStack.pop();
                                        CharacterStoreEntry cse = characterStore.newEntry();
                                        cse.put(en.token).put(GenericLexer.unquote(tok));
                                        opStack.push(expressionNodePool.next().of(ExpressionNode.LITERAL, cse.toImmutable(), Integer.MIN_VALUE, en.position));
                                    }
                                    break;
                                }
                            } else {
                                opStack.push(
                                        expressionNodePool.next().of(
                                                ExpressionNode.CONSTANT,
                                                lexer.immutableBetween(lexer.lastTokenPosition() - 1, lexer.getTokenHi()),
                                                0,
                                                lexer.lastTokenPosition()
                                        )
                                );
                                break;
                            }
                        }

                        if (prevBranch != BRANCH_DOT && nonLiteralBranches.excludes(prevBranch)) {
                            opStack.push(expressionNodePool.next().of(ExpressionNode.CONSTANT, GenericLexer.immutableOf(tok), 0, lexer.lastTokenPosition()));
                            break;
                        } else {
                            if (opStack.size() > 1) {
                                throw SqlException.$(lexer.lastTokenPosition(), "dangling expression");
                            }
                            lexer.unparse();
                            break OUT;
                        }
                    case 'N':
                    case 'n':
                    case 't':
                    case 'T':
                    case 'f':
                    case 'F':
                        if (SqlKeywords.isNanKeyword(tok) || SqlKeywords.isNullKeyword(tok)
                                || SqlKeywords.isTrueKeyword(tok) || SqlKeywords.isFalseKeyword(tok)) {
                            thisBranch = BRANCH_CONSTANT;
                            // If the token is a number, then add it to the output queue.
                            opStack.push(expressionNodePool.next().of(ExpressionNode.CONSTANT, GenericLexer.immutableOf(tok), 0, lexer.lastTokenPosition()));
                            break;
                        }
                        processDefaultBranch = true;
                        break;
                    case '*':
                        // special case for tab.*
                        if (prevBranch == BRANCH_DOT) {
                            thisBranch = BRANCH_LITERAL;
                            final ExpressionNode en = opStack.peek();
                            if (en != null && en.type != ExpressionNode.CONTROL) {
                                // leverage the fact '*' is dedicated token and it returned from cache
                                // therefore lexer.tokenHi does not move when * follows dot without whitespace
                                // e.g. 'a.*'
                                GenericLexer.FloatingSequence fs = (GenericLexer.FloatingSequence) en.token;
                                final int p = lexer.lastTokenPosition();
                                if (GenericLexer.WHITESPACE_CH.excludes(lexer.getContent().charAt(p - 1))) {
                                    fs.setHi(p + 1);
                                } else {
                                    // in this case we have whitespace, e.g. 'a. *'
                                    processDefaultBranch = true;
                                }
                            } else {
                                opStack.push(
                                        expressionNodePool.next().of(
                                                ExpressionNode.CONSTANT,
                                                lexer.immutableBetween(lexer.lastTokenPosition() - 1, lexer.getTokenHi()),
                                                0,
                                                lexer.lastTokenPosition()
                                        )
                                );
                            }
                            break;
                        }
                    default:
                        processDefaultBranch = true;
                        break;
                }

                if (processDefaultBranch) {
                    OperatorExpression op;
                    if ((op = OperatorExpression.opMap.get(tok)) != null) {

                        thisBranch = BRANCH_OPERATOR;

                        // If the token is an operator, o1, then:
                        // while there is an operator token, o2, at the top of the operator stack, and either
                        // o1 is left-associative and its precedence is less than or equal to that of o2, or
                        // o1 is right associative, and has precedence less than that of o2,
                        //        then pop o2 off the operator stack, onto the output queue;
                        // push o1 onto the operator stack.

                        int operatorType = op.type;

                        if (thisChar == '-') {
                            switch (prevBranch) {
                                case BRANCH_OPERATOR:
                                case BRANCH_LEFT_BRACE:
                                case BRANCH_COMMA:
                                case BRANCH_NONE:
                                case BRANCH_CASE_CONTROL:
                                    // we have unary minus
                                    operatorType = OperatorExpression.UNARY;
                                    break;
                                default:
                                    break;
                            }
                        }

                        ExpressionNode other;
                        // UNARY operators must never pop BINARY ones regardless of precedence
                        // this is to maintain correctness of -a^b
                        while ((other = opStack.peek()) != null) {
                            boolean greaterPrecedence = (op.leftAssociative && op.precedence >= other.precedence) || (!op.leftAssociative && op.precedence > other.precedence);
                            if (greaterPrecedence &&
                                    (operatorType != OperatorExpression.UNARY || (operatorType == OperatorExpression.UNARY && other.paramCount == 1))) {
                                argStackDepth = onNode(listener, other, argStackDepth);
                                opStack.pop();
                            } else {
                                break;
                            }
                        }
                        node = expressionNodePool.next().of(
                                op.type == OperatorExpression.SET ? ExpressionNode.SET_OPERATION : ExpressionNode.OPERATION,
                                op.token,
                                op.precedence,
                                lexer.lastTokenPosition()
                        );
                        if (operatorType == OperatorExpression.UNARY) {
                            node.paramCount = 1;
                        } else {
                            node.paramCount = 2;
                        }
                        opStack.push(node);
                    } else if (caseCount > 0 || nonLiteralBranches.excludes(thisBranch)) {

                        // here we handle literals, in case of "case" statement some of these literals
                        // are going to flush operation stack
                        if (Chars.toLowerCaseAscii(thisChar) == 'c' && SqlKeywords.isCaseKeyword(tok)) {
                            caseCount++;
                            paramCountStack.push(paramCount);
                            paramCount = 0;

                            caseBraceCountStack.push(braceCount);
                            braceCount = 0;

                            argStackDepthStack.push(argStackDepth);
                            argStackDepth = 0;
                            opStack.push(expressionNodePool.next().of(ExpressionNode.FUNCTION, "case", Integer.MAX_VALUE, lexer.lastTokenPosition()));
                            thisBranch = BRANCH_CASE_START;
                            continue;
                        }

                        thisBranch = BRANCH_LITERAL;

                        if (caseCount > 0) {
                            switch (Chars.toLowerCaseAscii(thisChar)) {
                                case 'e':
                                    if (SqlKeywords.isEndKeyword(tok)) {
                                        if (prevBranch == BRANCH_CASE_CONTROL) {
                                            throw missingArgs(lexer.lastTokenPosition());
                                        }

                                        if (paramCount == 0) {
                                            throw SqlException.$(lexer.lastTokenPosition(), "'when' expected");
                                        }

                                        // If the token is a right parenthesis:
                                        // Until the token at the top of the stack is a left parenthesis, pop operators off the stack onto the output queue.
                                        // Pop the left parenthesis from the stack, but not onto the output queue.
                                        //        If the token at the top of the stack is a function token, pop it onto the output queue.
                                        //        If the stack runs out without finding a left parenthesis, then there are mismatched parentheses.
                                        while ((node = opStack.pop()) != null && !SqlKeywords.isCaseKeyword(node.token)) {
                                            argStackDepth = onNode(listener, node, argStackDepth);
                                        }

                                        // 'when/else' have been clearing argStackDepth to ensure
                                        // expressions between 'when' and 'when' do not pick up arguments outside of scope
                                        // now we need to restore stack depth before 'case' entry
                                        if (argStackDepthStack.notEmpty()) {
                                            argStackDepth += argStackDepthStack.pop();
                                        }

                                        if (caseBraceCountStack.notEmpty()) {
                                            braceCount = caseBraceCountStack.pop();
                                        }

                                        node.paramCount = paramCount;
                                        // we also add number of 'case' arguments to original stack depth
                                        argStackDepth = onNode(listener, node, argStackDepth + paramCount);

                                        // make sure we restore paramCount
                                        if (paramCountStack.notEmpty()) {
                                            paramCount = paramCountStack.pop();
                                        }

                                        caseCount--;
                                        continue;
                                    }
                                    // fall through
                                case 'w':
                                case 't':
                                    int keywordIndex = caseKeywords.get(tok);
                                    if (keywordIndex > -1) {

                                        if (prevBranch == BRANCH_CASE_CONTROL) {
                                            throw missingArgs(lexer.lastTokenPosition());
                                        }

                                        // we need to track argument consumption so that operators and functions
                                        // do no steal parameters outside of local 'case' scope
                                        int argCount = 0;
                                        while ((node = opStack.pop()) != null && !SqlKeywords.isCaseKeyword(node.token)) {
                                            argStackDepth = onNode(listener, node, argStackDepth);
                                            argCount++;
                                        }

                                        if (paramCount == 0) {
                                            if (argCount == 0) {
                                                // this is 'case when', we will
                                                // indicate that this is regular 'case' to the rewrite logic
                                                onNode(listener, expressionNodePool.next().of(ExpressionNode.LITERAL, null, Integer.MIN_VALUE, -1), argStackDepth);
                                            }
                                            paramCount++;
                                        }

                                        switch (keywordIndex) {
                                            case 0: // when
                                            case 2: // else
                                                if ((paramCount % 2) == 0) {
                                                    throw SqlException.$(lexer.lastTokenPosition(), "'then' expected");
                                                }
                                                break;
                                            default: // then
                                                if ((paramCount % 2) != 0) {
                                                    throw SqlException.$(lexer.lastTokenPosition(), "'when' expected");
                                                }
                                                break;
                                        }

                                        if (node != null) {
                                            opStack.push(node);
                                        }

                                        argStackDepth = 0;
                                        paramCount++;
                                        thisBranch = BRANCH_CASE_CONTROL;
                                        continue;
                                    }
                                    break;
                                default:
                                    break;
                            }
                        }

                        if (prevBranch == BRANCH_DOT) {
                            // this deals with 'table.column' situations
                            ExpressionNode en = opStack.peek();
                            if (en == null) {
                                throw SqlException.$(lexer.lastTokenPosition(), "qualifier expected");
                            }
                            // two possibilities here:
                            // 1. 'a.b'
                            // 2. 'a. b'

                            final int p = lexer.lastTokenPosition();
                            if (GenericLexer.WHITESPACE_CH.contains(lexer.getContent().charAt(p - 1))) {
                                // 'a. b'
                                lexer.unparse();
                                break;
                            }

                            if (Chars.isQuoted(tok) || en.token instanceof CharacterStore.NameAssemblerCharSequence) {
                                // replacing node, must remove old one from stack
                                opStack.pop();
                                // this was more analogous to 'a."b"'
                                CharacterStoreEntry cse = characterStore.newEntry();
                                cse.put(en.token).put(GenericLexer.unquote(tok));
                                opStack.push(expressionNodePool.next().of(ExpressionNode.LITERAL, cse.toImmutable(), Integer.MIN_VALUE, en.position));
                            } else {
                                final GenericLexer.FloatingSequence fsA = (GenericLexer.FloatingSequence) en.token;
                                // vanilla 'a.b', just concat tokens efficiently
                                fsA.setHi(lexer.getTokenHi());
                            }
                        } else {
                            // If the token is a function token, then push it onto the stack.
                            opStack.push(expressionNodePool.next().of(ExpressionNode.LITERAL, GenericLexer.unquote(tok), Integer.MIN_VALUE, lexer.lastTokenPosition()));
                        }
                    } else {
                        // literal can be at start of input, after a bracket or part of an operator
                        // all other cases are illegal and will be considered end-of-input
                        lexer.unparse();
                        break;
                    }
                }
            }

            while ((node = opStack.pop()) != null) {

                if (node.token.charAt(0) == '(') {
                    throw SqlException.$(node.position, "unbalanced (");
                }

                if (SqlKeywords.isCaseKeyword(node.token)) {
                    throw SqlException.$(node.position, "unbalanced 'case'");
                }

                if (node.type == ExpressionNode.CONTROL) {
                    // break on any other control node to allow parser to be reenterable
                    // put control node back on stack because we don't own it
                    opStack.push(node);
                    break;
                }

                argStackDepth = onNode(listener, node, argStackDepth);
            }

        } catch (SqlException e) {
            opStack.clear();
            backupCastBraceCountStack.clear();
            backupParamCountStack.clear();
            backupArgStackDepthStack.clear();
            throw e;
        } finally {
            argStackDepthStack.clear();
            paramCountStack.clear();
            castBraceCountStack.clear();
        }
    }

    private int processLambdaQuery(GenericLexer lexer, ExpressionParserListener listener, int argStackDepth) throws SqlException {
        // It is highly likely this expression parser will be re-entered when
        // parsing sub-query. To prevent sub-query consuming operation stack we must add a
        // control node, which would prevent such consumption

        // precedence must be max value to make sure control node isn't
        // consumed as parameter to a greedy function
        opStack.push(expressionNodePool.next().of(ExpressionNode.CONTROL, "|", Integer.MAX_VALUE, lexer.lastTokenPosition()));

        final int paramCountStackSize = paramCountStack.size();
        paramCountStack.copyTo(backupParamCountStack, paramCountStackSize);

        final int argStackDepthStackSize = argStackDepthStack.size();
        argStackDepthStack.copyTo(backupArgStackDepthStack, argStackDepthStackSize);

        final int castBraceCountStackSize = castBraceCountStack.size();
        castBraceCountStack.copyTo(backupCastBraceCountStack, castBraceCountStackSize);

        final int caseBraceCountStackSize = caseBraceCountStack.size();
        caseBraceCountStack.copyTo(backupCaseBraceCountStack, caseBraceCountStackSize);

        int pos = lexer.lastTokenPosition();
        // allow sub-query to parse "select" keyword
        lexer.unparse();

        ExpressionNode node = expressionNodePool.next().of(ExpressionNode.QUERY, null, 0, pos);
        // validate is Query is allowed
        onNode(listener, node, argStackDepth);
        // we can compile query if all is well
        node.queryModel = sqlParser.parseAsSubQuery(lexer, null);
        argStackDepth = onNode(listener, node, argStackDepth);

        // pop our control node if sub-query hasn't done it
        ExpressionNode control = opStack.peek();
        if (control != null && control.type == ExpressionNode.CONTROL && Chars.equals(control.token, '|')) {
            opStack.pop();
        }

        backupParamCountStack.copyTo(paramCountStack, paramCountStackSize);
        backupArgStackDepthStack.copyTo(argStackDepthStack, argStackDepthStackSize);
        backupCastBraceCountStack.copyTo(castBraceCountStack, castBraceCountStackSize);
        backupCaseBraceCountStack.copyTo(caseBraceCountStack, caseBraceCountStackSize);

        // re-introduce closing brace to this loop
        lexer.unparse();
        return argStackDepth;
    }

    static {
        nonLiteralBranches.add(BRANCH_RIGHT_BRACE);
        nonLiteralBranches.add(BRANCH_CONSTANT);
        nonLiteralBranches.add(BRANCH_LITERAL);
        nonLiteralBranches.add(BRANCH_LAMBDA);
//        nonLiteralBranches.add(BRANCH_DOT);

        caseKeywords.put("when", 0);
        caseKeywords.put("then", 1);
        caseKeywords.put("else", 2);
    }
}
