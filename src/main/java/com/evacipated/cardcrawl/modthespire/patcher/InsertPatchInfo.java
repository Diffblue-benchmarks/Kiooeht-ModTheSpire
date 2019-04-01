package com.evacipated.cardcrawl.modthespire.patcher;

import java.util.List;

import com.evacipated.cardcrawl.modthespire.Loader;
import com.evacipated.cardcrawl.modthespire.lib.SpireInsertPatch;
import com.evacipated.cardcrawl.modthespire.lib.SpireReturn;
import javassist.*;

public class InsertPatchInfo extends PatchInfo
{

    public static class LineNumberAndPatchType {
        public int lineNumber;
        public int relativeLineNumber;
        public InsertPatchType patchType;

        public LineNumberAndPatchType(int lineNumber) {
            this.lineNumber = lineNumber;
            this.patchType = InsertPatchType.ABSOLUTE;
        }

        public LineNumberAndPatchType(int lineNumber, int relativeLineNumber) {
            this.lineNumber = lineNumber;
            this.relativeLineNumber = relativeLineNumber;
            this.patchType = InsertPatchType.RELATIVE;
        }

    }

    public static enum InsertPatchType {
        ABSOLUTE, RELATIVE
    }

    private SpireInsertPatch info;
    private List<LineNumberAndPatchType> locs;

    public InsertPatchInfo(SpireInsertPatch info, List<LineNumberAndPatchType> locs, CtBehavior ctMethodToPatch, CtMethod patchMethod)
    {
        super(ctMethodToPatch, patchMethod);
        this.info = info;
        this.locs = locs;
    }

    @Override
    protected String debugMsg()
    {
        StringBuilder msgBuilder = new StringBuilder("");
        for (LineNumberAndPatchType patchLoc : locs) {
            switch(patchLoc.patchType) {
            case ABSOLUTE:
                msgBuilder.append("Adding Insert @ " + patchLoc.lineNumber + "...\n");
                break;
            case RELATIVE:
                msgBuilder.append("Adding Insert @ r" + patchLoc.relativeLineNumber + " (abs:" + patchLoc.lineNumber + ")...\n");
                break;
            }
        }
        return msgBuilder.toString();
    }

    @Override
    public int patchOrdering()
    {
        return -2;
    }
    
    private void doPatch(int loc) throws NotFoundException, ClassNotFoundException, CannotCompileException, PatchingException {
        CtClass returnType = patchMethod.getReturnType();
        boolean hasEarlyReturn = false;
        if (ctMethodToPatch instanceof CtMethod
            && !returnType.equals(CtPrimitiveType.voidType)
            && returnType.equals(returnType.getClassPool().get(SpireReturn.class.getName()))) {

            hasEarlyReturn = true;
        } else if (ctMethodToPatch instanceof CtConstructor
            && !returnType.equals(CtPrimitiveType.voidType)
            && returnType.equals(returnType.getClassPool().get(SpireReturn.class.getName()))) {

            hasEarlyReturn = true;
        }

        CtClass[] insertParamTypes = patchMethod.getParameterTypes();
        Object[][] insertParamAnnotations = patchMethod.getParameterAnnotations();
        int insertParamsStartIndex = ctMethodToPatch.getParameterTypes().length;
        if (!Modifier.isStatic(ctMethodToPatch.getModifiers())) {
            insertParamsStartIndex += 1;
        }
        String[] localVarTypeNames = new String[insertParamAnnotations.length - insertParamsStartIndex];
        for (int i = insertParamsStartIndex; i < insertParamAnnotations.length; ++i) {
            if (paramByRef(insertParamAnnotations[i])) {
                if (!insertParamTypes[i].isArray()) {
                    throw new ByRefParameterNotArrayException(i);
                } else {
                    localVarTypeNames[i - insertParamsStartIndex] = insertParamTypes[i].getName();
                }
            }
        }

        String src = "{\n";
        if (info != null) {
            // Setup array holders for each local variable
            for (int i = 0; i < info.localvars().length; ++i) {
                if (i >= localVarTypeNames.length) {
                    throw new PatchingException("Insufficient method parameters to accept localvars");
                }
                if (localVarTypeNames[i] != null) {
                    src += localVarTypeNames[i] + " __" + info.localvars()[i] + " = new " + localVarTypeNames[i] + "{" + info.localvars()[i] + "};\n";
                }
            }
        }

        if (hasEarlyReturn) {
            src += SpireReturn.class.getName() + " opt = ";
        }

        src += patchMethod.getDeclaringClass().getName() + "." + patchMethod.getName() + "(";
        if (!Modifier.isStatic(ctMethodToPatch.getModifiers())) {
            if (src.charAt(src.length()-1) != '(') {
                src += ", ";
            }
            src += "$0";
        }
        if (src.charAt(src.length()-1) != '(') {
            src += ", ";
        }
        src += "$$";
        if (info != null) {
            for (int i = 0; i < info.localvars().length; ++i) {
                src += ", ";
                if (localVarTypeNames[i] != null) {
                    src += "__";
                }
                src += info.localvars()[i];
            }
        }
        src += ");\n";

        String src2 = src;
        if (info != null) {
            // Set local variables to changed values
            for (int i = 0; i < info.localvars().length; ++i) {
                if (localVarTypeNames[i] != null) {
                    src += info.localvars()[i] + " = ";
                    src2 += info.localvars()[i] + " = ";

                    String typename = paramByRefTypename(insertParamAnnotations[i + insertParamsStartIndex]);
                    if (!typename.isEmpty()) {
                        src += "(" + typename + ")";
                        src2 += "(com.megacrit.cardcrawl." + typename + ")";
                    }
                    src += "__" + info.localvars()[i] + "[0];\n";
                    src2 += "__" + info.localvars()[i] + "[0];\n";
                }
            }
        }

        if (hasEarlyReturn) {
            String earlyReturn = "if (opt.isPresent()) { return";
            if (ctMethodToPatch instanceof CtMethod && !((CtMethod) ctMethodToPatch).getReturnType().equals(CtPrimitiveType.voidType)) {
                CtClass toPatchReturnType = ((CtMethod) ctMethodToPatch).getReturnType();
                String toPatchReturnTypeName = toPatchReturnType.getName();
                if (toPatchReturnType.isPrimitive()) {
                    if (toPatchReturnType.equals(CtPrimitiveType.intType)) {
                        toPatchReturnTypeName = "Integer";
                    } else if (toPatchReturnType.equals(CtPrimitiveType.charType)) {
                        toPatchReturnTypeName = "Character";
                    } else {
                        toPatchReturnTypeName = toPatchReturnTypeName.substring(0, 1).toUpperCase() + toPatchReturnTypeName.substring(1);
                    }
                    earlyReturn += " (";
                }
                earlyReturn += " (" + toPatchReturnTypeName + ")opt.get()";
                if (toPatchReturnType.isPrimitive()) {
                    earlyReturn += ")." + toPatchReturnType.getName() + "Value()";
                }
            }
            earlyReturn += "; }\n";

            src += earlyReturn;
            src2 += earlyReturn;
        }

        src += "}";
        src2 += "}";

        try {
            ctMethodToPatch.insertAt(loc, src);
            if (Loader.DEBUG) {
                System.out.println(src);
            }
        } catch (CannotCompileException e) {
            try {
                ctMethodToPatch.insertAt(loc, src2);
                if (Loader.DEBUG) {
                    System.out.println(src2);
                }
            } catch (CannotCompileException e2) {
                if (Loader.DEBUG) {
                    System.out.println(src);
                }
                throw e;
            }
        }
    }

    @Override
    public void doPatch() throws PatchingException
    {
        for (LineNumberAndPatchType patchLoc : locs) {
            try {
                doPatch(patchLoc.lineNumber);
            } catch (CannotCompileException | NotFoundException | ClassNotFoundException e) {
                throw new PatchingException(e);
            }
        }
    }
}
