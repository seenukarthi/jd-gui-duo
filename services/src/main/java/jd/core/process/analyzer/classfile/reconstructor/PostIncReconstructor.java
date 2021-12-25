/*******************************************************************************
 * Copyright (C) 2007-2019 Emmanuel Dupuy GPLv3
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 ******************************************************************************/
package jd.core.process.analyzer.classfile.reconstructor;

import org.apache.bcel.Const;

import java.util.List;

import jd.core.model.instruction.bytecode.ByteCodeConstants;
import jd.core.model.instruction.bytecode.instruction.*;
import jd.core.process.analyzer.util.ReconstructorUtil;

/*
 * Recontruction des post-incrementations depuis le motif :
 * DupStore( i )
 * ...
 * {?Store | PutField | PutStatic}( DupLoad +/- 1 )
 * ...
 * ???( DupLoad )
 */
public class PostIncReconstructor
{
    private PostIncReconstructor() {
        super();
    }

    public static void reconstruct(List<Instruction> list)
    {
        int length = list.size();

        for (int dupStoreIndex=0; dupStoreIndex<length; dupStoreIndex++)
        {
            if (list.get(dupStoreIndex).getOpcode() != ByteCodeConstants.DUPSTORE) {
				continue;
			}

            // DupStore trouv�
            DupStore dupstore = (DupStore)list.get(dupStoreIndex);

            int xstorePutfieldPutstaticIndex = dupStoreIndex;

            while (++xstorePutfieldPutstaticIndex < length)
            {
                Instruction i = list.get(xstorePutfieldPutstaticIndex);
                BinaryOperatorInstruction boi = null;

                switch (i.getOpcode())
                {
                case Const.ASTORE:
                    if (dupstore.getObjectref().getOpcode() == Const.ALOAD &&
                        ((IndexInstruction)i).getIndex() == ((IndexInstruction)dupstore.getObjectref()).getIndex())
                    {
                        i = ((StoreInstruction)i).getValueref();
                        if (i.getOpcode() == ByteCodeConstants.CONVERT || i.getOpcode() == ByteCodeConstants.IMPLICITCONVERT) {
							i = ((ConvertInstruction)i).getValue();
						}
                        if (i.getOpcode() == ByteCodeConstants.BINARYOP) {
							boi = (BinaryOperatorInstruction)i;
						}
                    }
                    break;
                case Const.ISTORE:
                    if (dupstore.getObjectref().getOpcode() == Const.ILOAD &&
                        ((IndexInstruction)i).getIndex() == ((IndexInstruction)dupstore.getObjectref()).getIndex())
                    {
                        i = ((StoreInstruction)i).getValueref();
                        if (i.getOpcode() == ByteCodeConstants.CONVERT || i.getOpcode() == ByteCodeConstants.IMPLICITCONVERT) {
							i = ((ConvertInstruction)i).getValue();
						}
                        if (i.getOpcode() == ByteCodeConstants.BINARYOP) {
							boi = (BinaryOperatorInstruction)i;
						}
                    }
                    break;
                case ByteCodeConstants.STORE:
                    if (dupstore.getObjectref().getOpcode() == ByteCodeConstants.LOAD &&
                        ((IndexInstruction)i).getIndex() == ((IndexInstruction)dupstore.getObjectref()).getIndex())
                    {
                        i = ((StoreInstruction)i).getValueref();
                        if (i.getOpcode() == ByteCodeConstants.CONVERT || i.getOpcode() == ByteCodeConstants.IMPLICITCONVERT) {
							i = ((ConvertInstruction)i).getValue();
						}
                        if (i.getOpcode() == ByteCodeConstants.BINARYOP) {
							boi = (BinaryOperatorInstruction)i;
						}
                    }
                    break;
                case Const.PUTFIELD:
                    if (dupstore.getObjectref().getOpcode() == Const.GETFIELD &&
                        ((IndexInstruction)i).getIndex() == ((IndexInstruction)dupstore.getObjectref()).getIndex())
                    {
                        i = ((PutField)i).getValueref();
                        if (i.getOpcode() == ByteCodeConstants.CONVERT || i.getOpcode() == ByteCodeConstants.IMPLICITCONVERT) {
							i = ((ConvertInstruction)i).getValue();
						}
                        if (i.getOpcode() == ByteCodeConstants.BINARYOP) {
							boi = (BinaryOperatorInstruction)i;
						}
                    }
                    break;
                case Const.PUTSTATIC:
                    if (dupstore.getObjectref().getOpcode() == Const.GETSTATIC &&
                        ((IndexInstruction)i).getIndex() == ((IndexInstruction)dupstore.getObjectref()).getIndex())
                    {
                        i = ((PutStatic)i).getValueref();
                        if (i.getOpcode() == ByteCodeConstants.CONVERT || i.getOpcode() == ByteCodeConstants.IMPLICITCONVERT) {
							i = ((ConvertInstruction)i).getValue();
						}
                        if (i.getOpcode() == ByteCodeConstants.BINARYOP) {
							boi = (BinaryOperatorInstruction)i;
						}
                    }
                    break;
                }

                if (boi == null ||
                    boi.getValue1().getOpcode() != ByteCodeConstants.DUPLOAD ||
                    boi.getValue1().getOffset() != dupstore.getOffset() ||
                    boi.getValue2().getOpcode() != ByteCodeConstants.ICONST &&
                     boi.getValue2().getOpcode() != ByteCodeConstants.LCONST &&
                     boi.getValue2().getOpcode() != ByteCodeConstants.DCONST &&
                     boi.getValue2().getOpcode() != ByteCodeConstants.FCONST) {
					continue;
				}

                ConstInstruction ci = (ConstInstruction)boi.getValue2();

                if (ci.getValue() != 1) {
					continue;
				}

                int value;

                if ("+".equals(boi.getOperator())) {
					value = 1;
				} else if ("-".equals(boi.getOperator())) {
					value = -1;
				} else {
					continue;
				}

                Instruction inc = new IncInstruction(
                    ByteCodeConstants.POSTINC, boi.getOffset(), boi.getLineNumber(),
                    dupstore.getObjectref(), value);

                ReconstructorUtil.replaceDupLoad(
                        list, xstorePutfieldPutstaticIndex+1, dupstore, inc);

                list.remove(xstorePutfieldPutstaticIndex);
                list.remove(dupStoreIndex);
                dupStoreIndex--;
                length = list.size();
                break;
            }
        }
    }
}
