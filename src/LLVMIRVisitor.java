import org.antlr.v4.runtime.*;
import org.antlr.v4.runtime.tree.TerminalNode;
import org.bytedeco.javacpp.Pointer;
import org.bytedeco.javacpp.PointerPointer;
import org.bytedeco.llvm.LLVM.*;
import Scope.*;

import java.util.Stack;

import static org.bytedeco.llvm.global.LLVM.*;
import static org.bytedeco.llvm.global.LLVM.LLVMBuildGEP;

public class LLVMIRVisitor extends SysYParserBaseVisitor<LLVMValueRef> {
	private GlobalScope globalScope = null;
	private Scope currentScope = null;
	private int localScopeCounter = 0;
	
	private final LLVMModuleRef module = LLVMModuleCreateWithName("module");
	private final LLVMBuilderRef builder = LLVMCreateBuilder();
	private final LLVMTypeRef i32Type = LLVMInt32Type();
	private final LLVMTypeRef voidType = LLVMVoidType();
	private final LLVMValueRef zero = LLVMConstInt(i32Type, 0, 0);
	private boolean isReturned = false;
	private final Stack<LLVMBasicBlockRef> blockStack = new Stack<>();
	private LLVMValueRef currentFunction = null;
	
	public LLVMIRVisitor() {
		LLVMInitializeCore(LLVMGetGlobalPassRegistry());
		LLVMLinkInMCJIT();
		LLVMInitializeNativeAsmPrinter();
		LLVMInitializeNativeAsmParser();
		LLVMInitializeNativeTarget();
	}
	
	public LLVMModuleRef getModule() {
		return module;
	}
	
	private String toDecimalInteger(String tokenText) {
		if (tokenText.startsWith("0x") || tokenText.startsWith("0X")) {
			tokenText = String.valueOf(Integer.parseInt(tokenText.substring(2), 16));
		} else if (tokenText.startsWith("0")) {
			tokenText = String.valueOf(Integer.parseInt(tokenText, 8));
		}
		return tokenText;
	}
	
	@Override
	public LLVMValueRef visitTerminal(TerminalNode node) {
		Token symbol = node.getSymbol();
		int symbolType = symbol.getType();
		
		if (symbolType == SysYParser.INTEGR_CONST) {
			int number = (int) Long.parseLong(toDecimalInteger(node.getText()));
			return LLVMConstInt(i32Type, number, 0);
		}
		
		return super.visitTerminal(node);
	}
	
	@Override
	public LLVMValueRef visitProgram(SysYParser.ProgramContext ctx) {
		currentScope = globalScope = new GlobalScope(null);
		LLVMValueRef ret = super.visitProgram(ctx);
		currentScope = currentScope.getEnclosingScope();
		return ret;
	}
	
	private LLVMTypeRef getTypeRef(String typeName) {
		if (typeName.equals("int")) {
			return i32Type;
		} else {
			return voidType;
		}
	}
	
	@Override
	public LLVMValueRef visitFuncDef(SysYParser.FuncDefContext ctx) {
		int paramsCount = 0;
		if (ctx.funcFParams() != null) {
			paramsCount = ctx.funcFParams().funcFParam().size();
		}
		
		PointerPointer<Pointer> paramsTypes = new PointerPointer<>(paramsCount);
		for (int i = 0; i < paramsCount; ++i) {
			SysYParser.FuncFParamContext funcFParamContext = ctx.funcFParams().funcFParam(i);
			String paramTypeName = funcFParamContext.bType().getText();
			LLVMTypeRef paramType = getTypeRef(paramTypeName);
			paramsTypes.put(i, paramType);
		}
		
		String retTypeName = ctx.funcType().getText();
		LLVMTypeRef retType = getTypeRef(retTypeName);
		LLVMTypeRef functionType = LLVMFunctionType(retType, paramsTypes, paramsCount, 0);
		
		String functionName = ctx.IDENT().getText();
		currentFunction = LLVMAddFunction(module, functionName, functionType);
		LLVMBasicBlockRef entry = LLVMAppendBasicBlock(currentFunction, functionName + "_entry");
		LLVMPositionBuilderAtEnd(builder, blockStack.push(entry));
		
		for (int i = 0; i < paramsCount; ++i) {
			SysYParser.FuncFParamContext funcFParamContext = ctx.funcFParams().funcFParam(i);
			String paramTypeName = funcFParamContext.bType().getText();
			LLVMTypeRef paramType = getTypeRef(paramTypeName);
			String varName = ctx.funcFParams().funcFParam(i).IDENT().getText();
			LLVMValueRef varPointer = LLVMBuildAlloca(builder, paramType, "pointer_" + varName);
			currentScope.define(varName, varPointer);
			LLVMValueRef argValue = LLVMGetParam(currentFunction, i);
			LLVMBuildStore(builder, argValue, varPointer);
		}
		
		currentScope.define(functionName, currentFunction);
		currentScope = new LocalScope(currentScope);
		isReturned = false;
		super.visitFuncDef(ctx);
		currentScope = currentScope.getEnclosingScope();
		
		if (!isReturned) {
			LLVMBuildRet(builder, null);
		}
		isReturned = false;
		blockStack.pop();
		return currentFunction;
	}
	
	@Override
	public LLVMValueRef visitBlock(SysYParser.BlockContext ctx) {
		LocalScope localScope = new LocalScope(currentScope);
		String localScopeName = localScope.getName() + (localScopeCounter++);
		localScope.setName(localScopeName);
		currentScope = localScope;
		LLVMValueRef ret = super.visitBlock(ctx);
		currentScope = currentScope.getEnclosingScope();
		return ret;
	}
	
	@Override
	public LLVMValueRef visitVarDecl(SysYParser.VarDeclContext ctx) {
		String typeName = ctx.bType().getText();
		
		for (SysYParser.VarDefContext varDefContext : ctx.varDef()) {
			LLVMTypeRef varType = getTypeRef(typeName);
			String varName = varDefContext.IDENT().getText();
			int elementCount = 0;
			
			for (SysYParser.ConstExpContext constExpContext : varDefContext.constExp()) {
				elementCount = Integer.parseInt(toDecimalInteger(constExpContext.getText()));
				varType = LLVMArrayType(varType, elementCount);
			}
			
			LLVMValueRef varPointer = null;
			if (currentScope == globalScope) {
				varPointer = LLVMAddGlobal(module, varType, "pointer_" + varName);
				LLVMSetInitializer(varPointer, zero);
			} else {
				varPointer = LLVMBuildAlloca(builder, varType, "pointer_" + varName);
			}
			
			if (varDefContext.ASSIGN() != null) {
				SysYParser.ExpContext expContext = varDefContext.initVal().exp();
				if (expContext != null) {
					LLVMValueRef initVal = visit(expContext);
					if (currentScope == globalScope) {
						LLVMSetInitializer(varPointer, initVal);
					} else {
						LLVMBuildStore(builder, initVal, varPointer);
					}
				} else {
					int initValCount = varDefContext.initVal().initVal().size();
					if (currentScope == globalScope) {
						PointerPointer pointerPointer = new PointerPointer<>(elementCount);
						for (int i = 0; i < elementCount; ++i) {
							if (i < initValCount) {
								pointerPointer.put(i, this.visit(varDefContext.initVal().initVal(i).exp()));
							} else {
								pointerPointer.put(i, zero);
							}
						}
						LLVMValueRef initArray = LLVMConstArray(varType, pointerPointer, elementCount);
						LLVMSetInitializer(varPointer, initArray);
					} else {
						LLVMValueRef[] initArray = new LLVMValueRef[elementCount];
						for (int i = 0; i < elementCount; ++i) {
							if (i < initValCount) {
								initArray[i] = this.visit(varDefContext.initVal().initVal(i).exp());
							} else {
								initArray[i] = LLVMConstInt(i32Type, 0, 0);
							}
						}
						buildGEP(elementCount, varPointer, initArray);
					}
				}
			}
			
			currentScope.define(varName, varPointer);
		}
		
		return null;
	}
	
	private void buildGEP(int elementCount, LLVMValueRef varPointer, LLVMValueRef[] initArray) {
		LLVMValueRef[] arrayPointer = new LLVMValueRef[2];
		arrayPointer[0] = zero;
		for (int i = 0; i < elementCount; i++) {
			arrayPointer[1] = LLVMConstInt(i32Type, i, 0);
			PointerPointer<LLVMValueRef> indexPointer = new PointerPointer<>(arrayPointer);
			LLVMValueRef elementPtr = LLVMBuildGEP(builder, varPointer, indexPointer, 2, "GEP_" + i);
			LLVMBuildStore(builder, initArray[i], elementPtr);
		}
	}
	
	@Override
	public LLVMValueRef visitConstDecl(SysYParser.ConstDeclContext ctx) {
		String typeName = ctx.bType().getText();
		
		for (SysYParser.ConstDefContext constDefContext : ctx.constDef()) {
			LLVMTypeRef varType = getTypeRef(typeName);
			String varName = constDefContext.IDENT().getText();
			int elementCount = 0;
			
			for (SysYParser.ConstExpContext constExpContext : constDefContext.constExp()) {
				elementCount = Integer.parseInt(toDecimalInteger(constExpContext.getText()));
				varType = LLVMArrayType(varType, elementCount);
			}
			
			LLVMValueRef varPointer = null;
			if (currentScope == globalScope) {
				varPointer = LLVMAddGlobal(module, varType, "pointer_" + varName);
				LLVMSetInitializer(varPointer, zero);
			} else {
				varPointer = LLVMBuildAlloca(builder, varType, "pointer_" + varName);
			}
			
			SysYParser.ConstExpContext constExpContext = constDefContext.constInitVal().constExp();
			if (constExpContext != null) {
				LLVMValueRef initVal = visit(constExpContext);
				if (currentScope == globalScope) {
					LLVMSetInitializer(varPointer, initVal);
				} else {
					LLVMBuildStore(builder, initVal, varPointer);
				}
			} else {
				int initValCount = constDefContext.constInitVal().constInitVal().size();
				if (currentScope == globalScope) {
					PointerPointer<LLVMValueRef> pointerPointer = new PointerPointer<>(elementCount);
					for (int i = 0; i < elementCount; ++i) {
						if (i < initValCount) {
							pointerPointer.put(i, this.visit(constDefContext.constInitVal().constInitVal(i).constExp()));
						} else {
							pointerPointer.put(i, zero);
						}
					}
					LLVMValueRef initArray = LLVMConstArray(varType, pointerPointer, elementCount);
					LLVMSetInitializer(varPointer, initArray);
				} else {
					LLVMValueRef[] initArray = new LLVMValueRef[elementCount];
					for (int i = 0; i < elementCount; ++i) {
						if (i < initValCount) {
							initArray[i] = this.visit(constDefContext.constInitVal().constInitVal(i).constExp());
						} else {
							initArray[i] = LLVMConstInt(i32Type, 0, 0);
						}
					}
					buildGEP(elementCount, varPointer, initArray);
				}
			}
			
			currentScope.define(varName, varPointer);
		}
		
		return null;
	}
	
	@Override
	public LLVMValueRef visitUnaryExp(SysYParser.UnaryExpContext ctx) {
		String operator = ctx.unaryOp().getText();
		LLVMValueRef expValue = visit(ctx.exp());
		switch (operator) {
			case "+": {
				return expValue;
			}
			case "-": {
				return LLVMBuildNeg(builder, expValue, "neg_");
			}
			case "!": {
				long numValue = LLVMConstIntGetZExtValue(expValue);
				if (numValue == 0) {
					return LLVMConstInt(i32Type, 1, 0);
				} else {
					return LLVMConstInt(i32Type, 0, 0);
				}
			}
			default: {
			}
		}
		
		return null;
	}
	
	@Override
	public LLVMValueRef visitParenExp(SysYParser.ParenExpContext ctx) {
		return this.visit(ctx.exp());
	}
	
	@Override
	public LLVMValueRef visitAddExp(SysYParser.AddExpContext ctx) {
		LLVMValueRef valueRef1 = visit(ctx.exp(0));
		LLVMValueRef valueRef2 = visit(ctx.exp(1));
		if (ctx.PLUS() != null) {
			return LLVMBuildAdd(builder, valueRef1, valueRef2, "add_");
		} else {
			return LLVMBuildSub(builder, valueRef1, valueRef2, "sub_");
		}
	}
	
	@Override
	public LLVMValueRef visitMulExp(SysYParser.MulExpContext ctx) {
		LLVMValueRef valueRef1 = visit(ctx.exp(0));
		LLVMValueRef valueRef2 = visit(ctx.exp(1));
		if (ctx.MUL() != null) {
			return LLVMBuildMul(builder, valueRef1, valueRef2, "mul_");
		} else if (ctx.DIV() != null) {
			return LLVMBuildSDiv(builder, valueRef1, valueRef2, "sdiv_");
		} else {
			return LLVMBuildSRem(builder, valueRef1, valueRef2, "srem_");
		}
	}
	
	@Override
	public LLVMValueRef visitLValExp(SysYParser.LValExpContext ctx) {
		LLVMValueRef lValPointer = this.visitLVal(ctx.lVal());
		return LLVMBuildLoad(builder, lValPointer, ctx.lVal().getText());
	}
	
	@Override
	public LLVMValueRef visitLVal(SysYParser.LValContext ctx) {
		String lValName = ctx.IDENT().getText();
		LLVMValueRef varPointer = currentScope.resolve(lValName);
		if (ctx.exp().size() == 0) {
			return varPointer;
		} else {
			lValName += "[" + ctx.exp(0).getText() + "]";
			LLVMValueRef[] arrayPointer = new LLVMValueRef[2];
			arrayPointer[0] = zero;
			LLVMValueRef index = this.visit(ctx.exp(0));
			arrayPointer[1] = index;
			PointerPointer<LLVMValueRef> indexPointer = new PointerPointer<>(arrayPointer);
			return LLVMBuildGEP(builder, varPointer, indexPointer, 2, "pointer_" + lValName);
		}
	}
	
	@Override
	public LLVMValueRef visitAssignment(SysYParser.AssignmentContext ctx) {
		LLVMValueRef lValPointer = this.visitLVal(ctx.lVal());
		LLVMValueRef exp = this.visit(ctx.exp());
		return LLVMBuildStore(builder, exp, lValPointer);
	}
	
	@Override
	public LLVMValueRef visitCondExp(SysYParser.CondExpContext ctx) {
		return this.visit(ctx.exp());
	}
	
	@Override
	public LLVMValueRef visitNumExp(SysYParser.NumExpContext ctx) {
		return this.visit(ctx.number());
	}
	
	@Override
	public LLVMValueRef visitNumber(SysYParser.NumberContext ctx) {
		return this.visit(ctx.INTEGR_CONST());
	}
	
	@Override
	public LLVMValueRef visitFuncCallExp(SysYParser.FuncCallExpContext ctx) {
		String functionName = ctx.IDENT().getText();
		LLVMValueRef function = currentScope.resolve(functionName);
		PointerPointer<Pointer> args = null;
		int argsCount = 0;
		if (ctx.funcRParams() != null) {
			argsCount = ctx.funcRParams().param().size();
			args = new PointerPointer<>(argsCount);
			for (int i = 0; i < argsCount; ++i) {
				SysYParser.ParamContext paramContext = ctx.funcRParams().param(i);
				SysYParser.ExpContext expContext = paramContext.exp();
				args.put(i, this.visit(expContext));
			}
		}
		return LLVMBuildCall(builder, function, args, argsCount, "");
	}
	
	@Override
	public LLVMValueRef visitReturnStmt(SysYParser.ReturnStmtContext ctx) {
		LLVMValueRef result = null;
		if (ctx.exp() != null) {
			result = visit(ctx.exp());
		}
		isReturned = true;
		return LLVMBuildRet(builder, result);
	}
	
	@Override
	public LLVMValueRef visitIfStmt(SysYParser.IfStmtContext ctx) {
		LLVMValueRef condVal = this.visit(ctx.cond());
		LLVMValueRef cmpResult = LLVMBuildICmp(builder, LLVMIntNE, zero, condVal, "cmp_result");
		LLVMBasicBlockRef trueBlock = LLVMAppendBasicBlock(currentFunction, "trueBlock");
		LLVMBasicBlockRef falseBlock = LLVMAppendBasicBlock(currentFunction, "falseBlock");
		LLVMBasicBlockRef afterBlock = LLVMAppendBasicBlock(currentFunction, "afterBlock");
		
		LLVMBuildCondBr(builder, cmpResult, trueBlock, falseBlock);
		
		LLVMPositionBuilderAtEnd(builder, trueBlock);
		this.visit(ctx.stmt(0));
		LLVMBuildBr(builder, afterBlock);
		
		LLVMPositionBuilderAtEnd(builder, falseBlock);
		if (ctx.ELSE() != null) {
			this.visit(ctx.stmt(1));
		}
		LLVMBuildBr(builder, afterBlock);
		
		LLVMPositionBuilderAtEnd(builder, afterBlock);
		return null;
	}
	
	@Override
	public LLVMValueRef visitCompareExp(SysYParser.CompareExpContext ctx) {
		LLVMValueRef lVal = this.visit(ctx.cond(0));
		LLVMValueRef rVal = this.visit(ctx.cond(1));
		if (ctx.LT() != null) {
			return LLVMBuildICmp(builder, LLVMIntSLT, lVal, rVal, "LT");
		} else if (ctx.GT() != null) {
			return LLVMBuildICmp(builder, LLVMIntSGT, lVal, rVal, "GT");
		} else if (ctx.LE() != null) {
			return LLVMBuildICmp(builder, LLVMIntSLE, lVal, rVal, "LE");
		} else {
			return LLVMBuildICmp(builder, LLVMIntSGE, lVal, rVal, "GE");
		}
	}
	
	@Override
	public LLVMValueRef visitRelationExp(SysYParser.RelationExpContext ctx) {
		LLVMValueRef lVal = this.visit(ctx.cond(0));
		LLVMValueRef rVal = this.visit(ctx.cond(1));
		if (ctx.NEQ() != null) {
			return LLVMBuildICmp(builder, LLVMIntNE, lVal, rVal, "NEQ");
		} else {
			return LLVMBuildICmp(builder, LLVMIntEQ, lVal, rVal, "EQ");
		}
	}
	
	@Override
	public LLVMValueRef visitAndExp(SysYParser.AndExpContext ctx) {
		LLVMValueRef lVal = this.visit(ctx.cond(0));
		LLVMValueRef rVal = this.visit(ctx.cond(1));
		return LLVMBuildICmp(builder, LLVMAnd, lVal, rVal, "AND");
	}
	
	@Override
	public LLVMValueRef visitOrExp(SysYParser.OrExpContext ctx) {
		LLVMValueRef lVal = this.visit(ctx.cond(0));
		LLVMValueRef rVal = this.visit(ctx.cond(1));
		return LLVMBuildICmp(builder, LLVMOr, lVal, rVal, "OR");
	}
}
