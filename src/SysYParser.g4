parser grammar SysYParser;

options {
    tokenVocab = SysYLexer;
}

program : compUnit;

// 编译单元
compUnit : (funcDef | decl)+ EOF;

// 下面是其他的语法单元定义

// 声明
decl : constDecl | varDecl;

// 常量声明
constDecl : CONST bType constDef (COMMA constDef)* SEMICOLON;

// 基本类型
bType : INT;

// 常数定义
constDef : IDENT (L_BRACKT constExp R_BRACKT)* ASSIGN constInitVal;

// 常量初值
constInitVal : constExp | L_BRACE (constInitVal (COMMA constInitVal)*)? R_BRACE;

// 变量声明
varDecl : bType varDef (COMMA varDef)* SEMICOLON;

// 变量定义
varDef : IDENT (L_BRACKT constExp R_BRACKT)* (ASSIGN initVal)?;

// 变量初值
initVal : exp | L_BRACE (initVal (COMMA initVal)*)? R_BRACE;

// 函数定义
funcDef : funcType IDENT L_PAREN (funcFParams)? R_PAREN block;

// 函数类型
funcType : VOID | INT;

// 函数形参表
funcFParams : funcFParam (COMMA funcFParam)*;

// 函数形参
funcFParam : bType IDENT (L_BRACKT R_BRACKT (L_BRACKT exp R_BRACKT)*)?;

// 语句块
block : L_BRACE (blockItem)* R_BRACE;

// 语句块项
blockItem : decl | stmt;

// 语句
stmt : lVal ASSIGN exp SEMICOLON # assignment
    | (exp)? SEMICOLON # possibleExp
    | block # blockStmt
    | IF L_PAREN cond R_PAREN stmt (ELSE stmt)? # ifStmt
    | WHILE L_PAREN cond R_PAREN stmt # whileStmt
    | BREAK SEMICOLON # breakStmt
    | CONTINUE SEMICOLON # continueStmt
    | RETURN (exp)? SEMICOLON # returnStmt
    ;

// 表达式
exp: L_PAREN exp R_PAREN # parenExp
	| lVal # lValExp
	| number # numExp
	| IDENT L_PAREN funcRParams? R_PAREN # funcCallExp
	| unaryOp exp # unaryExp
	| exp (MUL | DIV | MOD) exp # mulExp
	| exp (PLUS | MINUS) exp # addExp
	;


// 条件表达式
cond: exp # condExp
	| cond (LT | GT | LE | GE) cond # compareExp
	| cond (EQ | NEQ) cond # relationExp
	| cond AND cond # andExp
	| cond OR cond # orExp
	;


// 左值表达式
lVal : IDENT (L_BRACKT exp R_BRACKT)*;

// 基本表达式
//primaryExp : L_PAREN exp R_PAREN | lVal | number;

// 数值
number : INTEGR_CONST;

// 一元表达式
//unaryExp : primaryExp | IDENT L_PAREN (funcRParams)? R_PAREN | unaryOp unaryExp;

// 单目运算符
unaryOp : PLUS | MINUS | NOT;

// 函数实参表， from TA
funcRParams : param (COMMA param)*;

// from TA
param : exp;

//// 乘除模表达式
//mulExp : unaryOp | mulExp (MUL | DIV | MOD) unaryExp;
//
//// 加减表达式
//addExp : mulExp | addExp (PLUS | MINUS) mulExp;
//
//// 关系表达式
//relExp : addExp | relExp (LT | GT | LE | GE) addExp;
//
//// 相等性表达式
//eqExp : relExp | eqExp (EQ | NEQ) relExp;
//
//// 逻辑与表达式
//lAndExp : eqExp | lAndExp LAND eqExp;
//
//// 逻辑或表达式
//lOrExp : lAndExp | lOrExp LOR lAndExp;

// 常量表达式
constExp : exp;