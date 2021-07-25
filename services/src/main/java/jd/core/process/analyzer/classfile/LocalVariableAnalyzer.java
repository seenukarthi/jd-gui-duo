/**
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
 */
package jd.core.process.analyzer.classfile;

import org.apache.bcel.Const;
import org.apache.bcel.classfile.ConstantFieldref;
import org.apache.bcel.classfile.ConstantNameAndType;
import org.jd.core.v1.util.StringConstants;

import java.util.List;

import jd.core.model.classfile.*;
import jd.core.model.classfile.attribute.AttributeSignature;
import jd.core.model.instruction.bytecode.ByteCodeConstants;
import jd.core.model.instruction.bytecode.instruction.*;
import jd.core.process.analyzer.classfile.visitor.AddCheckCastVisitor;
import jd.core.process.analyzer.classfile.visitor.SearchInstructionByOffsetVisitor;
import jd.core.process.analyzer.instruction.bytecode.util.ByteCodeUtil;
import jd.core.process.analyzer.util.InstructionUtil;
import jd.core.process.analyzer.variable.DefaultVariableNameGenerator;
import jd.core.util.SignatureUtil;
import jd.core.util.UtilConstants;

public class LocalVariableAnalyzer
{
	private LocalVariableAnalyzer() {
	}
	/**
	 * Indexe de signature pour les variables locales de type inconnu. Si le
	 * type de la variable n'a pu être determiné, la variable sera type
	 * 'Object'.
	 */
	private static final int UNDEFINED_TYPE = -1;
	/**
	 * Indexe de signature pour les variables locales de type numérique inconnu.
	 * Si le type de la variable n'a pu être determiné, la variable sera type
	 * 'int'.
	 */
	private static final int NUMBER_TYPE = -2;
	/**
	 * Indexe de signature pour les variables locales de type 'Object' et
	 * nécéssitant l'insertion d'instructions 'cast'.
	 */
	private static final int OBJECT_TYPE = -3;

	public static void analyze(
			ClassFile classFile, Method method,
			DefaultVariableNameGenerator variableNameGenerator,
			List<Instruction> list, List<Instruction> listForAnalyze)
	{
		ConstantPool constants = classFile.getConstantPool();
		variableNameGenerator.clearLocalNames();

		// DEBUG String debugClassName = classFile.getInternalClassName();
		// DEBUG String debugMethodName = constants.getConstantUtf8(method.nameIndex);

		// Reconstruction de la Liste des variables locales
		byte[] code = method.getCode();
		int codeLength = code == null ? 0 : code.length;
		LocalVariables localVariables = method.getLocalVariables();

		if (localVariables == null)
		{
			// Ajout d'entrées dans le tableau pour les parametres
			localVariables = new LocalVariables();
			method.setLocalVariables(localVariables);

			// Add this
			if ((method.accessFlags & Const.ACC_STATIC) == 0)
			{
				int nameIndex = constants.addConstantUtf8(
						StringConstants.THIS_LOCAL_VARIABLE_NAME);
				int signatureIndex =
						constants.addConstantUtf8(classFile.getInternalClassName());
				LocalVariable lv =
						new LocalVariable(0, codeLength, nameIndex, signatureIndex, 0);
				localVariables.add(lv);
			}

			if (method.getNameIndex() == constants.instanceConstructorIndex &&
					classFile.isAInnerClass() &&
					(classFile.accessFlags & Const.ACC_STATIC) == 0)
			{
				// Add outer this
				int nameIndex = constants.addConstantUtf8(
						StringConstants.OUTER_THIS_LOCAL_VARIABLE_NAME);
				String internalClassName = classFile.getInternalClassName();
				int lastInnerClassSeparatorIndex =
						internalClassName.lastIndexOf(StringConstants.INTERNAL_INNER_SEPARATOR);

				String internalOuterClassName =
						internalClassName.substring(0, lastInnerClassSeparatorIndex) + ';';

				int signatureIndex = constants.addConstantUtf8(internalOuterClassName);
				LocalVariable lv =
						new LocalVariable(0, codeLength, nameIndex, signatureIndex, 1);
				localVariables.add(lv);
			}

			// Add Parameters
			analyzeMethodParameter(
					classFile, constants, method, localVariables,
					variableNameGenerator, codeLength);

			localVariables.setIndexOfFirstLocalVariable(localVariables.size());

			if (code != null)
			{
				generateMissingMonitorLocalVariables(
						constants, localVariables, listForAnalyze);
			}
		}
		else
		{
			// Traitement des entrées correspondant aux parametres
			AttributeSignature as = method.getAttributeSignature();
			String methodSignature = constants.getConstantUtf8(
					as==null ? method.getDescriptorIndex() : as.signatureIndex);

			int indexOfFirstLocalVariable =
					((method.accessFlags & Const.ACC_STATIC) == 0 ? 1 : 0) +
					SignatureUtil.getParameterSignatureCount(methodSignature);

			if (indexOfFirstLocalVariable > localVariables.size())
			{
				// Dans le cas des méthodes générée automatiquement par le
				// compilateur (comme par exemple les méthode des enums), le
				// tableau des variables locales est incomplet.
				// Add Parameters
				analyzeMethodParameter(
						classFile, constants, method, localVariables,
						variableNameGenerator, codeLength);
			}

			localVariables.setIndexOfFirstLocalVariable(
					indexOfFirstLocalVariable);

			if (code != null)
			{
				generateMissingMonitorLocalVariables(
						constants, localVariables, listForAnalyze);
				checkLocalVariableRanges(
						constants, code, localVariables,
						variableNameGenerator, listForAnalyze);
			}

			// La fusion des variables locales genere des erreurs. Mise en
			// commentaire a la version 0.5.3.
			//  fr.oseo.fui.actions.partenaire.FicheInformationAction:
			//   InterlocuteurBO interlocuteur;
			//   for (InterlocuteurBO partenaire = projet.getPartenaires().iterator(); partenaire.hasNext(); )
			//   {
			//    interlocuteur = (InterlocuteurBO)partenaire.next();
			//    ...
			//   }
			//   ...
			//   for (partenaire = projet.getPartenaires().iterator(); partenaire.hasNext(); )
			//   {
			//    interlocuteur = (InterlocuteurBO)partenaire.next();
			//    ...
			//   }
			//MergeLocalVariables(localVariables);
		}

		// Add local variables
		// Create new local variables, set range and type
		if (code != null)
		{
			String returnedSignature = getReturnedSignature(classFile, method);

			analyzeMethodCode(
					constants, localVariables, list, listForAnalyze,
					returnedSignature);

			// Upgrade byte type to char type
			// Substitution des types byte par char dans les instructions
			// bipush et sipush
			setConstantTypes(
					constants, localVariables,
					list, listForAnalyze, returnedSignature);

			initialyzeExceptionLoad(listForAnalyze, localVariables);
		}

		generateLocalVariableNames(
				constants, localVariables, variableNameGenerator);
	}

	private static void analyzeMethodParameter(
			ClassFile classFile, ConstantPool constants,
			Method method, LocalVariables localVariables,
			DefaultVariableNameGenerator variableNameGenerator, int codeLength)
	{
		// Le descripteur et la signature sont differentes pour les
		// constructeurs des Enums !
		AttributeSignature as = method.getAttributeSignature();
		String methodSignature = constants.getConstantUtf8(
				as == null ? method.getDescriptorIndex() : as.signatureIndex);
		List<String> parameterTypes =
				SignatureUtil.getParameterSignatures(methodSignature);

		if (parameterTypes != null)
		{
			// Arguments
			// Constructeur des classes interne non static :
			// - var 1: outer this => ne pas generer de nom
			// Constructeur des Enum :
			// Descripteur:
			// - var 1: nom de la valeur => ne pas afficher
			// - var 2: index de la valeur => ne pas afficher
			// Signature:
			// - variableIndex = 1 + 1 + 1
			// Le premier parametre des méthodes non statiques est 'this'
			boolean staticMethodFlag =
					(method.accessFlags & Const.ACC_STATIC) != 0;
			int variableIndex = staticMethodFlag ? 0 : 1;

			int firstVisibleParameterCounter = 0;

			if (method.getNameIndex() == constants.instanceConstructorIndex)
			{
				if ((classFile.accessFlags & Const.ACC_ENUM) != 0)
				{
					if (as == null) {
						firstVisibleParameterCounter = 2;
					} else {
						variableIndex = 3;
					}
				} else if (classFile.isAInnerClass() && (classFile.accessFlags & Const.ACC_STATIC) == 0) {
					firstVisibleParameterCounter = 1;
				}
			}

			int anonymousClassDepth = 0;
			ClassFile anonymousClassFile = classFile;

			while (anonymousClassFile != null &&
					anonymousClassFile.getInternalAnonymousClassName() != null)
			{
				anonymousClassDepth++;
				anonymousClassFile = anonymousClassFile.getOuterClass();
			}

			final int length = parameterTypes.size();

			final int varargsParameterIndex;

			if ((method.accessFlags & Const.ACC_VARARGS) == 0)
			{
				varargsParameterIndex = Integer.MAX_VALUE;
			}
			else
			{
				varargsParameterIndex = length - 1;
			}

			for (int parameterIndex=0; parameterIndex<length; parameterIndex++)
			{
				final String signature = parameterTypes.get(parameterIndex);

				if (/* (parameterIndex >= firstVisibleParameterCounter) && */
						localVariables.getLocalVariableWithIndexAndOffset(variableIndex, 0) == null)
				{
					boolean appearsOnceFlag = signatureAppearsOnceInParameters(
							parameterTypes, firstVisibleParameterCounter,
							length, signature);
					final String name =
							variableNameGenerator.generateParameterNameFromSignature(
									signature, appearsOnceFlag,
									parameterIndex==varargsParameterIndex,
									anonymousClassDepth);

					int nameIndex = constants.addConstantUtf8(name);
					int signatureIndex = constants.addConstantUtf8(signature);
					LocalVariable lv = new LocalVariable(
							0, codeLength, nameIndex, signatureIndex, variableIndex);
					localVariables.add(lv);
				}

				final char firstChar = signature.charAt(0);
				variableIndex +=
						firstChar == 'D' || firstChar == 'J' ? 2 : 1;
			}
		}
	}

	private static void generateMissingMonitorLocalVariables(
			ConstantPool constants, LocalVariables localVariables,
			List<Instruction> listForAnalyze)
	{
		int length = listForAnalyze.size();

		Instruction instruction;
		MonitorEnter mEnter;
		int monitorLocalVariableIndex;
		int monitorLocalVariableOffset;
		int monitorLocalVariableLength;
		int monitorExitCount;
		int j;
		LocalVariable lv;
		for (int i=1; i<length; i++)
		{
			instruction = listForAnalyze.get(i);

			if (instruction.opcode != Const.MONITORENTER) {
				continue;
			}

			mEnter = (MonitorEnter)instruction;
			monitorLocalVariableLength = 1;

			if (mEnter.objectref.opcode == ByteCodeConstants.DUPLOAD)
			{
				/* DupStore( ? ) AStore( DupLoad ) MonitorEnter( DupLoad ) */
				instruction = listForAnalyze.get(i-1);
				if (instruction.opcode != Const.ASTORE) {
					continue;
				}
				AStore astore = (AStore)instruction;
				if (astore.valueref.opcode != ByteCodeConstants.DUPLOAD) {
					continue;
				}
				DupLoad dupload1 = (DupLoad)mEnter.objectref;
				DupLoad dupload2 = (DupLoad)astore.valueref;
				if (dupload1.dupStore != dupload2.dupStore) {
					continue;
				}
				monitorLocalVariableIndex = astore.index;
				monitorLocalVariableOffset = astore.offset;
			}
			else if (mEnter.objectref.opcode == Const.ALOAD)
			{
				/* AStore( ? ) MonitorEnter( ALoad ) */
				ALoad aload = (ALoad)mEnter.objectref;
				instruction = listForAnalyze.get(i-1);
				if (instruction.opcode != Const.ASTORE) {
					continue;
				}
				AStore astore = (AStore)instruction;
				if (astore.index != aload.index) {
					continue;
				}
				monitorLocalVariableIndex = astore.index;
				monitorLocalVariableOffset = astore.offset;
			}
			else
			{
				continue;
			}

			// Recherche des intructions MonitorExit correspondantes
			monitorExitCount = 0;
			// Recherche en avant
			j = i;
			while (++j < length)
			{
				instruction = listForAnalyze.get(j);
				if (instruction.opcode != Const.MONITOREXIT || ((MonitorExit)instruction).objectref.opcode != Const.ALOAD) {
					continue;
				}
				ALoad al = (ALoad)((MonitorExit)instruction).objectref;
				if (al.index == monitorLocalVariableIndex)
				{
					monitorLocalVariableLength =
							al.offset - monitorLocalVariableOffset;
					monitorExitCount++;
				}
			}

			if (monitorExitCount == 1)
			{
				// Recherche en arriere (Jikes 1.22)
				j = i;
				ALoad al;
				while (j-- > 0)
				{
					instruction = listForAnalyze.get(j);
					if (instruction.opcode != Const.MONITOREXIT || ((MonitorExit)instruction).objectref.opcode != Const.ALOAD) {
						continue;
					}
					al = (ALoad)((MonitorExit)instruction).objectref;
					if (al.index == monitorLocalVariableIndex)
					{
						monitorLocalVariableLength +=
								monitorLocalVariableOffset - al.offset;
						monitorLocalVariableOffset = al.offset;

						monitorExitCount++;
						break;
					}
				}
			}

			if (monitorExitCount < 2) {
				continue;
			}

			// Verification de l'existance d'une variable locale
			lv = localVariables.getLocalVariableWithIndexAndOffset(
					monitorLocalVariableIndex, monitorLocalVariableOffset);

			// Creation d'une variable locale
			if (lv == null ||
					lv.startPc+lv.length < monitorLocalVariableOffset+monitorLocalVariableLength)
			{
				int signatureIndex =
						constants.addConstantUtf8(StringConstants.INTERNAL_OBJECT_SIGNATURE);
				localVariables.add(new LocalVariable(
						monitorLocalVariableOffset, monitorLocalVariableLength,
						signatureIndex, signatureIndex, monitorLocalVariableIndex));
			}
		}
	}

	/**
	 * Verification de la portee de chaque variable : la portee generee par les
	 * compilateurs est incorrecte : elle commence une instruction trop tard!
	 * De plus, la longueur de la portee est tres importante. Elle est
	 * recalculée.
	 */
	private static void checkLocalVariableRanges(
			ConstantPool constants, byte[] code, LocalVariables localVariables,
			DefaultVariableNameGenerator variableNameGenerator,
			List<Instruction> listForAnalyze)
	{
		// Reset length
		int length = localVariables.size();

		// Remise à 1 de la longueur des portées
		for (int i=localVariables.getIndexOfFirstLocalVariable(); i<length; i++) {
			localVariables.getLocalVariableAt(i).length = 1;
		}

		// Update range
		length = listForAnalyze.size();

		Instruction instruction;
		for (int i=0; i<length; i++)
		{
			instruction = listForAnalyze.get(i);
			switch (instruction.opcode)
			{
			case ByteCodeConstants.PREINC:
			case ByteCodeConstants.POSTINC:
			{
				instruction = ((IncInstruction)instruction).value;
				if (instruction.opcode == Const.ILOAD ||
						instruction.opcode == ByteCodeConstants.LOAD) {
					checkLocalVariableRangesForIndexInstruction(
							code, localVariables, (IndexInstruction)instruction);
				}
			}
			break;
			case Const.ASTORE:
			{
				AStore astore = (AStore)instruction;
				// ExceptionLoad ?
				if (astore.valueref.opcode == ByteCodeConstants.EXCEPTIONLOAD)
				{
					ExceptionLoad el =
							(ExceptionLoad)astore.valueref;

					if (el.exceptionNameIndex != 0)
					{
						LocalVariable lv =
								localVariables.getLocalVariableWithIndexAndOffset(
										astore.index, astore.offset);

						if (lv == null)
						{
							// Variable non trouvée. Recherche de la variable avec
							// l'offset suivant car les compilateurs place 'startPc'
							// une instruction plus après.
							int nextOffset =
									ByteCodeUtil.nextInstructionOffset(code, astore.offset);
							lv = localVariables.getLocalVariableWithIndexAndOffset(
									astore.index, nextOffset);
							if (lv == null)
							{
								// Create a new local variable for exception
								lv = new LocalVariable(
										astore.offset, 1, -1,
										el.exceptionNameIndex, astore.index, true);
								localVariables.add(lv);
								String signature =
										constants.getConstantUtf8(el.exceptionNameIndex);
								boolean appearsOnce = signatureAppearsOnceInLocalVariables(
										localVariables, localVariables.size(),
										el.exceptionNameIndex);
								String name =
										variableNameGenerator.generateLocalVariableNameFromSignature(
												signature, appearsOnce);
								lv.nameIndex = constants.addConstantUtf8(name);
							}
							else
							{
								// Variable trouvée. Mise à jour de 'startPc' de la
								// portée.
								lv.updateRange(astore.offset);
							}
						}
					}
				}
				else if (i+1 < length &&
						astore.valueref.opcode == ByteCodeConstants.DUPLOAD &&
						listForAnalyze.get(i+1).opcode == Const.MONITORENTER)
				{
					// Monitor ?
					LocalVariable lv =
							localVariables.getLocalVariableWithIndexAndOffset(
									astore.index, astore.offset);
					if (lv == null)
					{
						MonitorEnter me = (MonitorEnter)listForAnalyze.get(i+1);
						if (me.objectref.opcode == ByteCodeConstants.DUPLOAD &&
								((DupLoad)astore.valueref).dupStore ==
								((DupLoad)me.objectref).dupStore)
						{
							// Create a new local variable for monitor
							int signatureIndex = constants.addConstantUtf8(
									StringConstants.INTERNAL_OBJECT_SIGNATURE);
							localVariables.add(new LocalVariable(
									astore.offset, 1, signatureIndex,
									signatureIndex, astore.index));
						}
						else
						{
							// Default case
							checkLocalVariableRangesForIndexInstruction(
									code, localVariables, astore);
						}
					}
				}
				else
				{
					// Default case
					checkLocalVariableRangesForIndexInstruction(
							code, localVariables, astore);
				}
			}
			break;
			case Const.ISTORE:
			case Const.ILOAD:
			case ByteCodeConstants.STORE:
			case ByteCodeConstants.LOAD:
			case Const.ALOAD:
			case Const.IINC:
				checkLocalVariableRangesForIndexInstruction(
						code, localVariables, (IndexInstruction)instruction);
				break;
			}
		}
	}

	private static void checkLocalVariableRangesForIndexInstruction(
			byte[] code, LocalVariables localVariables, IndexInstruction ii)
	{
		LocalVariable lv =
				localVariables.getLocalVariableWithIndexAndOffset(ii.index, ii.offset);

		if (lv == null)
		{
			// Variable non trouvée. Recherche de la variable avec
			// l'offset suivant car les compilateurs place 'startPc'
			// une instruction plus après.
			int nextOffset = ByteCodeUtil.nextInstructionOffset(code, ii.offset);
			lv = localVariables.getLocalVariableWithIndexAndOffset(ii.index, nextOffset);
			if (lv != null)
			{
				// Variable trouvée. Mise à jour de 'startPc' de la
				// portée.
				lv.updateRange(ii.offset);
			}
			else
			{
				// Mise à jour de la longueur de la portées de la
				// variable possedant le meme index et precedement
				// definie.
				lv = localVariables.searchLocalVariableWithIndexAndOffset(ii.index, ii.offset);
				if (lv != null) {
					lv.updateRange(ii.offset);
				}
			}
		}
		else
		{
			// Mise à jour de la longeur de la portée
			lv.updateRange(ii.offset);
		}
	}

	// La fusion des variables locales genere des erreurs. Mise en
	// commentaire a la version 0.5.3.
	//  fr.oseo.fui.actions.partenaire.FicheInformationAction:
	//   InterlocuteurBO interlocuteur;
	//   for (InterlocuteurBO partenaire = projet.getPartenaires().iterator(); partenaire.hasNext(); )
	//   {
	//    interlocuteur = (InterlocuteurBO)partenaire.next();
	//    ...
	//   }
	//   ...
	//   for (partenaire = projet.getPartenaires().iterator(); partenaire.hasNext(); )
	//   {
	//    interlocuteur = (InterlocuteurBO)partenaire.next();
	//    ...
	//   }
	/*
	 * Fusion des entrees du tableau possédants les memes numero de slot,
	 * le meme nom et le meme type. Le tableau genere pour le code suivant
	 * contient deux entrees pour la variable 'a' !
        int a;
        if (e == null)
            a = 1;
        else
            a = 2;
        System.out.println(a);
	 */
	//	private static void MergeLocalVariables(LocalVariables localVariables)
	//	{
	//		for (int i=localVariables.size()-1; i>0; --i)
	//		{
	//			LocalVariable lv1 = localVariables.getLocalVariableAt(i);
	//			for (int j=i-1; j>=0; --j)
	//			{
	//				LocalVariable lv2 = localVariables.getLocalVariableAt(j);
	//				if ((lv1.index == lv2.index) &&
	//					(lv1.signatureIndex == lv2.signatureIndex) &&
	//					(lv1.nameIndex == lv2.nameIndex))
	//				{
	//					localVariables.remove(i);
	//					lv2.updateRange(lv1.startPc);
	//					lv2.updateRange(lv1.startPc+lv1.length-1);
	//					break;
	//				}
	//			}
	//		}
	//	}

	// Create new local variables, set range and type, update attribute
	// 'exception'
	/**
	 * Strategie :
	 * 	- Recherche de tous les instructions '?store' et '?load'
	 *  - Determiner le type de la viariable
	 *  - Si la variable n'est pas encore definie, ajouter une entrée dans la
	 *    Liste
	 *  - Sinon, si le type est compatible
	 */
	private static void analyzeMethodCode(
			ConstantPool constants,
			LocalVariables localVariables, List<Instruction> list,
			List<Instruction> listForAnalyze, String returnedSignature)
	{
		// Recherche des instructions d'ecriture des variables locales.
		int length = listForAnalyze.size();

		for (int i=0; i<length; i++)
		{
			Instruction instruction = listForAnalyze.get(i);

			if (instruction.opcode == Const.ISTORE || instruction.opcode == ByteCodeConstants.STORE
					|| instruction.opcode == Const.ASTORE || instruction.opcode == Const.ILOAD
					|| instruction.opcode == ByteCodeConstants.LOAD || instruction.opcode == Const.ALOAD
					|| instruction.opcode == Const.IINC) {
				subAnalyzeMethodCode(
						constants, localVariables, listForAnalyze,
						((IndexInstruction)instruction).index, i,
						returnedSignature);
			}
		}

		// Analyse inverse
		boolean change;

		Instruction instruction;
		do
		{
			change = false;

			for (int i=0; i<length; i++)
			{
				instruction = listForAnalyze.get(i);
				switch (instruction.opcode)
				{
				case Const.ISTORE:
				{
					StoreInstruction si = (StoreInstruction)instruction;
					if (si.valueref.opcode == Const.ILOAD)
					{
						// Contrainte du type de la variable liée à ILoad par
						// le type de la variable liée à IStore.
						change |= reverseAnalyzeIStore(localVariables, si);
					}
				}
				break;
				case Const.PUTSTATIC:
				{
					PutStatic ps = (PutStatic)instruction;
					if (ps.valueref.opcode == Const.ILOAD || ps.valueref.opcode == Const.ALOAD) {
						// Contrainte du type de la variable liée à ILoad par
						// le type de la variable liée à PutStatic.
						LoadInstruction load = (LoadInstruction)ps.valueref;
						change |= reverseAnalyzePutStaticPutField(
								constants, localVariables, ps, load);
					}
				}
				break;
				case Const.PUTFIELD:
				{
					PutField pf = (PutField)instruction;
					if (pf.valueref.opcode == Const.ILOAD || pf.valueref.opcode == Const.ALOAD) {
						// Contrainte du type de la variable liée à ILoad
						// par le type de la variable liée à PutField.
						LoadInstruction load = (LoadInstruction)pf.valueref;
						change |= reverseAnalyzePutStaticPutField(
								constants, localVariables, pf, load);
					}
				}
				break;
				}
			}
		}
		while (change);

		// Selection d'un type pour les variables non encore typées.
		int internalObjectSignatureIndex =
				constants.addConstantUtf8(StringConstants.INTERNAL_OBJECT_SIGNATURE);

		length = localVariables.size();

		for (int i=0; i<length; i++)
		{
			LocalVariable lv = localVariables.getLocalVariableAt(i);

			switch (lv.signatureIndex)
			{
			case UNDEFINED_TYPE:
				lv.signatureIndex = constants.addConstantUtf8(
						StringConstants.INTERNAL_OBJECT_SIGNATURE);
				break;
			case NUMBER_TYPE:
				lv.signatureIndex = constants.addConstantUtf8(
						SignatureUtil.getSignatureFromTypesBitField(lv.typesBitField));
				break;
			case OBJECT_TYPE:
				// Plusieurs types sont affectés à la même variable. Le
				// decompilateur ne connait pas le graphe d'heritage des
				// classes decompilées. Le type de la variable est valué à
				// 'Object'. Des instructions 'cast' supplémentaires doivent
				// être ajoutés. Voir la limitation de JAD sur ce point.
				lv.signatureIndex = internalObjectSignatureIndex;
				break;
			}
		}

		LocalVariable lv;
		// Ajout d'instructions "cast"
		for (int i=0; i<length; i++)
		{
			lv = localVariables.getLocalVariableAt(i);
			if (lv.signatureIndex == internalObjectSignatureIndex) {
				addCastInstruction(constants, list, localVariables, lv);
			}
		}
	}

	/** Analyse du type de la variable locale No varIndex. */
	private static void subAnalyzeMethodCode(
			ConstantPool constants, LocalVariables localVariables,
			List<Instruction> listForAnalyze,
			int varIndex, int startIndex, String returnedSignature)
	{
		IndexInstruction firstInstruction =
				(IndexInstruction)listForAnalyze.get(startIndex);

		LocalVariable lv =
				localVariables.getLocalVariableWithIndexAndOffset(
						firstInstruction.index, firstInstruction.offset);

		if (lv != null)
		{
			// Variable locale deja traitee

			// Verification que l'attribut 'exception' est correctement
			// positionné.
			if (firstInstruction.opcode == Const.ASTORE)
			{
				AStore astore = (AStore)firstInstruction;
				if (astore.valueref.opcode == ByteCodeConstants.EXCEPTIONLOAD) {
					lv.exceptionOrReturnAddress = true;
				}
			}

			return;
		}

		final int length = listForAnalyze.size();

		Instruction instruction;
		// Recherche des instructions de lecture, d'ecriture et de comparaison
		// des variables locales.
		for (int i=startIndex; i<length; i++)
		{
			instruction = listForAnalyze.get(i);
			switch (instruction.opcode)
			{
			case Const.ISTORE:
				if (((IndexInstruction)instruction).index == varIndex) {
					analyzeIStore(constants, localVariables, instruction);
				}
				break;
			case ByteCodeConstants.STORE:
				if (((IndexInstruction)instruction).index == varIndex) {
					analyzeStore(constants, localVariables, instruction);
				}
				break;
			case Const.ASTORE:
				if (((IndexInstruction)instruction).index == varIndex) {
					analyzeAStore(constants, localVariables, instruction);
				}
				break;
			case ByteCodeConstants.PREINC:
			case ByteCodeConstants.POSTINC:
				instruction = ((IncInstruction)instruction).value;
				if (instruction.opcode != Const.ILOAD &&
						instruction.opcode != ByteCodeConstants.LOAD) {
					break;
				}
				// intended fall through
			case Const.ILOAD:
			case Const.IINC:
				if (((IndexInstruction)instruction).index == varIndex) {
					analyzeILoad(localVariables, instruction);
				}
				break;
			case ByteCodeConstants.LOAD:
			case ByteCodeConstants.EXCEPTIONLOAD:
				if (((IndexInstruction)instruction).index == varIndex) {
					analyzeLoad(localVariables, instruction);
				}
				break;
			case Const.ALOAD:
				if (((IndexInstruction)instruction).index == varIndex) {
					analyzeALoad(localVariables, instruction);
				}
				break;
			case Const.INVOKEINTERFACE:
			case Const.INVOKEVIRTUAL:
			case Const.INVOKESPECIAL:
			case Const.INVOKESTATIC:
				analyzeInvokeInstruction(
						constants, localVariables, instruction, varIndex);
				break;
			case ByteCodeConstants.BINARYOP:
				BinaryOperatorInstruction boi =
				(BinaryOperatorInstruction)instruction;
				analyzeBinaryOperator(
						constants, localVariables, instruction,
						boi.value1, boi.value2, varIndex);
				break;
			case ByteCodeConstants.IFCMP:
				IfCmp ic = (IfCmp)instruction;
				analyzeBinaryOperator(
						constants, localVariables, instruction,
						ic.value1, ic.value2, varIndex);
				break;
			case ByteCodeConstants.XRETURN:
				analyzeReturnInstruction(
						constants, localVariables, instruction,
						varIndex, returnedSignature);
				break;
			}
		}
	}

	private static void analyzeIStore(
			ConstantPool constants, LocalVariables localVariables,
			Instruction instruction)
	{
		StoreInstruction store = (StoreInstruction)instruction;
		int index = store.index;
		int offset = store.offset;

		LocalVariable lv =
				localVariables.searchLocalVariableWithIndexAndOffset(index, offset);
		String signature =
				store.getReturnedSignature(constants, localVariables);

		if (lv == null)
		{
			int typesBitField;

			if (signature == null)
			{
				if (store.valueref.opcode == Const.ILOAD)
				{
					ILoad iload = (ILoad)store.valueref;
					lv = localVariables.getLocalVariableWithIndexAndOffset(
							iload.index, iload.offset);
					typesBitField = lv == null ?
							ByteCodeConstants.TBF_INT_INT|ByteCodeConstants.TBF_INT_SHORT|
							ByteCodeConstants.TBF_INT_BYTE|ByteCodeConstants.TBF_INT_CHAR|
							ByteCodeConstants.TBF_INT_BOOLEAN:
								lv.typesBitField;
				}
				else
				{
					typesBitField =
							ByteCodeConstants.TBF_INT_INT|ByteCodeConstants.TBF_INT_SHORT|
							ByteCodeConstants.TBF_INT_BYTE|ByteCodeConstants.TBF_INT_CHAR|
							ByteCodeConstants.TBF_INT_BOOLEAN;
				}
			}
			else
			{
				typesBitField = SignatureUtil.createTypesBitField(signature);
			}

			localVariables.add(new LocalVariable(
					offset, 1, -1, NUMBER_TYPE, index, typesBitField));
		} else if (signature == null)
		{
			lv.updateRange(offset);
		}
		else
		{
			// Une variable est trouvée. Le type est il compatible ?
			int typesBitField =
					SignatureUtil.createTypesBitField(signature);
			switch (lv.signatureIndex)
			{
			case NUMBER_TYPE:
				if ((typesBitField & lv.typesBitField) != 0)
				{
					// Reduction de champ de bits
					lv.typesBitField &= typesBitField;
					lv.updateRange(offset);
				}
				else
				{
					// Type incompatible => creation de variables
					localVariables.add(new LocalVariable(
							offset, 1, -1, NUMBER_TYPE, index, typesBitField));
				}
				break;
			case UNDEFINED_TYPE:
			case OBJECT_TYPE:
				// Type incompatible => creation de variables
				localVariables.add(new LocalVariable(
						offset, 1, -1, NUMBER_TYPE, index, typesBitField));
				break;
			default:
				String signatureLV =
				constants.getConstantUtf8(lv.signatureIndex);
				int typesBitFieldLV =
						SignatureUtil.createTypesBitField(signatureLV);

				if ((typesBitField & typesBitFieldLV) != 0)
				{
					lv.updateRange(offset);
				}
				else
				{
					// Type incompatible => creation de variables
					localVariables.add(new LocalVariable(
							offset, 1, -1, NUMBER_TYPE, index, typesBitField));
				}
			}
		}
	}

	private static void analyzeILoad(
			LocalVariables localVariables, Instruction instruction)
	{
		IndexInstruction load = (IndexInstruction)instruction;
		int index = load.index;
		int offset = load.offset;

		LocalVariable lv =
				localVariables.searchLocalVariableWithIndexAndOffset(index, offset);

		if (lv == null)
		{
			// La premiere instruction utilisant ce slot est de type 'Load'.
			// Impossible de determiner le type d'entier pour le moment.
			localVariables.add(new LocalVariable(
					offset, 1, -1, NUMBER_TYPE, index,
					ByteCodeConstants.TBF_INT_INT|ByteCodeConstants.TBF_INT_SHORT|
					ByteCodeConstants.TBF_INT_BYTE|ByteCodeConstants.TBF_INT_CHAR|
					ByteCodeConstants.TBF_INT_BOOLEAN));
		}
		else
		{
			lv.updateRange(offset);
		}
	}

	private static void analyzeLoad(
			LocalVariables localVariables, Instruction instruction)
	{
		IndexInstruction load = (IndexInstruction)instruction;
		int index = load.index;
		int offset = load.offset;

		LocalVariable lv =
				localVariables.searchLocalVariableWithIndexAndOffset(index, offset);

		if (lv == null)
		{
			localVariables.add(new LocalVariable(
					offset, 1, -1, -1, index));
		}
		else
		{
			lv.updateRange(offset);
		}
	}

	private static void analyzeALoad(
			LocalVariables localVariables, Instruction instruction)
	{
		IndexInstruction load = (IndexInstruction)instruction;
		int index = load.index;
		int offset = load.offset;

		LocalVariable lv =
				localVariables.searchLocalVariableWithIndexAndOffset(index, offset);

		if (lv == null)
		{
			localVariables.add(new LocalVariable(
					offset, 1, -1, UNDEFINED_TYPE, index));
		}
		else
		{
			lv.updateRange(offset);
		}
	}

	private static void analyzeInvokeInstruction(
			ConstantPool constants, LocalVariables localVariables,
			Instruction instruction, int varIndex)
	{
		final InvokeInstruction invokeInstruction =
				(InvokeInstruction)instruction;
		final List<Instruction> args = invokeInstruction.args;
		final List<String> argSignatures =
				invokeInstruction.getListOfParameterSignatures(constants);
		final int nbrOfArgs = args.size();

		for (int j=0; j<nbrOfArgs; j++)
		{
			analyzeArgOrReturnedInstruction(
					constants, localVariables, args.get(j),
					varIndex, argSignatures.get(j));
		}
	}

	private static void analyzeArgOrReturnedInstruction(
			ConstantPool constants, LocalVariables localVariables,
			Instruction instruction, int varIndex, String signature)
	{
		LoadInstruction li;
		if (instruction.opcode == Const.ILOAD) {
			li = (LoadInstruction)instruction;
			if (li.index == varIndex)
			{
				LocalVariable lv =
						localVariables.searchLocalVariableWithIndexAndOffset(li.index, li.offset);
				if (lv != null) {
					lv.typesBitField &=
							SignatureUtil.createArgOrReturnBitFields(signature);
				}
			}
		} else if (instruction.opcode == Const.ALOAD) {
			li = (LoadInstruction)instruction;
			if (li.index == varIndex)
			{
				LocalVariable lv =
						localVariables.searchLocalVariableWithIndexAndOffset(
								li.index, li.offset);
				if (lv != null)
				{
					if (lv.signatureIndex == UNDEFINED_TYPE) {
						lv.signatureIndex =
								constants.addConstantUtf8(signature);
					} else if (lv.signatureIndex == NUMBER_TYPE) {
						new Throwable("type inattendu").printStackTrace();
						// NE PAS GENERER DE CONFLIT DE TYPE LORSQUE LE TYPE
						// D'UNE VARIABLE EST DIFFERENT DU TYPE D'UN PARAMETRE.
						/* case OBJECT_TYPE:
                            break;
                        default:
                            String signature =
                                constants.getConstantUtf8(lv.signatureIndex);
                            String argSignature = argSignatures.get(j);

                            if (!argSignature.equals(signature) &&
                                !argSignature.equals(
                                    Constants.INTERNAL_OBJECT_SIGNATURE))
                            {
                                // La signature du parametre ne correspond pas
                                // a la signature de l'objet passé en parametre
                                lv.signatureIndex = OBJECT_TYPE;
                            }*/
					}
				}
			}
		}
	}

	/** Reduction de l'ensemble des types entiers. */
	private static void analyzeBinaryOperator(
			ConstantPool constants, LocalVariables localVariables,
			Instruction instruction, Instruction i1, Instruction i2,
			int varIndex)
	{
		if (
				(i1.opcode != Const.ILOAD || ((ILoad)i1).index != varIndex) &&
				(i2.opcode != Const.ILOAD || ((ILoad)i2).index != varIndex)
				) {
			return;
		}

		LocalVariable lv1 = i1.opcode == Const.ILOAD ?
				localVariables.searchLocalVariableWithIndexAndOffset(
						((ILoad)i1).index, i1.offset) : null;

		LocalVariable lv2 = i2.opcode == Const.ILOAD ?
				localVariables.searchLocalVariableWithIndexAndOffset(
						((ILoad)i2).index, i2.offset) : null;

		if (lv1 != null)
		{
			lv1.updateRange(instruction.offset);
			if (lv2 != null) {
				lv2.updateRange(instruction.offset);
			}

			if (lv1.signatureIndex == NUMBER_TYPE)
			{
				// Reduction des types de lv1
				if (lv2 != null)
				{
					if (lv2.signatureIndex == NUMBER_TYPE)
					{
						// Reduction des types de lv1 & lv2
						lv1.typesBitField &= lv2.typesBitField;
						lv2.typesBitField &= lv1.typesBitField;
					}
					else
					{
						lv1.signatureIndex = lv2.signatureIndex;
					}
				}
				else
				{
					String signature =
							i2.getReturnedSignature(constants, localVariables);

					if (SignatureUtil.isIntegerSignature(signature))
					{
						int type = SignatureUtil.createTypesBitField(signature);
						if (type != 0) {
							lv1.typesBitField &= type;
						}
					}
				}
			}
			else if (lv2 != null && lv2.signatureIndex == NUMBER_TYPE)
			{
				// Reduction des types de lv2
				lv2.signatureIndex = lv1.signatureIndex;
			}
		}
		else if (lv2 != null)
		{
			lv2.updateRange(instruction.offset);

			if (lv2.signatureIndex == NUMBER_TYPE)
			{
				// Reduction des types de lv2
				String signature =
						i1.getReturnedSignature(constants, localVariables);

				if (SignatureUtil.isIntegerSignature(signature))
				{
					int type = SignatureUtil.createTypesBitField(signature);
					if (type != 0) {
						lv2.typesBitField &= type;
					}
				}
			}
		}
	}

	private static void analyzeReturnInstruction(
			ConstantPool constants, LocalVariables localVariables,
			Instruction instruction, int varIndex, String returnedSignature)
	{
		ReturnInstruction ri = (ReturnInstruction)instruction;
		analyzeArgOrReturnedInstruction(
				constants, localVariables, ri.valueref,
				varIndex, returnedSignature);
	}

	private static void analyzeStore(
			ConstantPool constants, LocalVariables localVariables,
			Instruction instruction)
	{
		StoreInstruction store = (StoreInstruction)instruction;
		int index = store.index;
		int offset = store.offset;

		LocalVariable lv =
				localVariables.searchLocalVariableWithIndexAndOffset(index, offset);
		String signature =
				instruction.getReturnedSignature(constants, localVariables);
		int signatureIndex =
				signature != null ? constants.addConstantUtf8(signature) : -1;

		if (lv == null || lv.signatureIndex != signatureIndex)
		{
			// variable non trouvée ou type incompatible => création de variable
			localVariables.add(new LocalVariable(
					offset, 1, -1, signatureIndex, index));
		} else {
			lv.updateRange(offset);
		}
	}

	private static void analyzeAStore(
			ConstantPool constants, LocalVariables localVariables,
			Instruction instruction)
	{
		StoreInstruction store = (StoreInstruction)instruction;
		int index = store.index;
		int offset = store.offset;

		LocalVariable lv =
				localVariables.searchLocalVariableWithIndexAndOffset(index, offset);
		String signatureInstruction =
				instruction.getReturnedSignature(constants, localVariables);
		int signatureInstructionIndex = signatureInstruction != null ?
				constants.addConstantUtf8(signatureInstruction) : UNDEFINED_TYPE;
		boolean isExceptionOrReturnAddress =
				store.valueref.opcode == ByteCodeConstants.EXCEPTIONLOAD ||
				store.valueref.opcode == ByteCodeConstants.RETURNADDRESSLOAD;

		if (lv == null || lv.exceptionOrReturnAddress ||
				isExceptionOrReturnAddress && lv.startPc + lv.length < offset)
		{
			localVariables.add(new LocalVariable(
					offset, 1, -1, signatureInstructionIndex, index,
					isExceptionOrReturnAddress));
		}
		else if (!isExceptionOrReturnAddress)
		{
			// Une variable est trouvée. Le type est il compatible ?
			if (lv.signatureIndex == UNDEFINED_TYPE)
			{
				// Cas particulier Jikes 1.2.2 bloc finally :
				//  Une instruction ALoad apparait avant AStore
				lv.signatureIndex = signatureInstructionIndex;
				lv.updateRange(offset);
			}
			else if (lv.signatureIndex == NUMBER_TYPE)
			{
				// Creation de variables
				localVariables.add(new LocalVariable(
						offset, 1, -1, signatureInstructionIndex, index));
			}
			else if (lv.signatureIndex == signatureInstructionIndex ||
					lv.signatureIndex == OBJECT_TYPE)
			{
				lv.updateRange(offset);
			}
			else
			{
				// Type incompatible => 2 cas :
				// 1) si une signature est de type 'Object' et la seconde est
				//    un type primitif, creation d'une nouvelle variable.
				// 2) si les deux signatures sont de type 'Object',
				//    modification du type de la variable en 'Object' puis
				//    ajout d'instruction cast.
				String signatureLV =
						constants.getConstantUtf8(lv.signatureIndex);

				if (SignatureUtil.isPrimitiveSignature(signatureLV))
				{
					// Creation de variables
					localVariables.add(new LocalVariable(
							offset, 1, -1, signatureInstructionIndex, index));
				} else {
					if (signatureInstructionIndex != UNDEFINED_TYPE)
					{
						// Modification du type de variable
						lv.signatureIndex = OBJECT_TYPE;
					}
					lv.updateRange(offset);
				}
			}
		}
	}

	/**
	 * Substitution des types byte par char dans les instructions
	 * bipush, sipush et iconst suivants les instructions istore et invoke.
	 */
	private static void setConstantTypes(
			ConstantPool constants,
			LocalVariables localVariables, List<Instruction> list,
			List<Instruction> listForAnalyze, String returnedSignature)
	{
		final int length = listForAnalyze.size();

		// Affection du type des constantes depuis les instructions mères
		for (int i=0; i<length; i++)
		{
			final Instruction instruction = listForAnalyze.get(i);

			switch (instruction.opcode)
			{
			case ByteCodeConstants.ARRAYSTORE:
				setConstantTypesArrayStore(
						constants, localVariables,
						(ArrayStoreInstruction)instruction);
				break;
			case ByteCodeConstants.BINARYOP:
			{
				BinaryOperatorInstruction boi =
						(BinaryOperatorInstruction)instruction;
				setConstantTypesBinaryOperator(
						constants, localVariables, boi.value1, boi.value2);
			}
			break;
			case ByteCodeConstants.IFCMP:
			{
				IfCmp ic = (IfCmp)instruction;
				setConstantTypesBinaryOperator(
						constants, localVariables, ic.value1, ic.value2);
			}
			break;
			case Const.INVOKEINTERFACE:
			case Const.INVOKEVIRTUAL:
			case Const.INVOKESPECIAL:
			case Const.INVOKESTATIC:
			case ByteCodeConstants.INVOKENEW:
				setConstantTypesInvokeInstruction(constants, instruction);
				break;
			case Const.ISTORE:
				setConstantTypesIStore(constants, localVariables, instruction);
				break;
			case Const.PUTFIELD:
			{
				PutField putField = (PutField)instruction;
				setConstantTypesPutFieldAndPutStatic(
						constants, putField.valueref, putField.index);
			}
			break;
			case Const.PUTSTATIC:
			{
				PutStatic putStatic = (PutStatic)instruction;
				setConstantTypesPutFieldAndPutStatic(
						constants, putStatic.valueref, putStatic.index);
			}
			break;
			case ByteCodeConstants.XRETURN:
			{
				setConstantTypesXReturn(instruction, returnedSignature);
			}
			break;
			}
		}

		Instruction instruction;
		// Determination des types des constantes apparaissant dans les
		// instructions 'TernaryOpStore'.
		for (int i=0; i<length; i++)
		{
			instruction = listForAnalyze.get(i);

			if (instruction.opcode == ByteCodeConstants.TERNARYOPSTORE)
			{
				TernaryOpStore tos = (TernaryOpStore)instruction;
				setConstantTypesTernaryOpStore(
						constants, localVariables, list, tos);
			}
		}
	}

	private static void setConstantTypesInvokeInstruction(
			ConstantPool constants,
			Instruction instruction)
	{
		final InvokeInstruction invokeInstruction =
				(InvokeInstruction)instruction;
		final List<Instruction> args = invokeInstruction.args;
		final List<String> types =
				invokeInstruction.getListOfParameterSignatures(constants);
		final int nbrOfArgs = args.size();

		Instruction arg;
		for (int j=0; j<nbrOfArgs; j++)
		{
			arg = args.get(j);

			if (arg.opcode == Const.BIPUSH || arg.opcode == ByteCodeConstants.ICONST
					|| arg.opcode == Const.SIPUSH) {
				((IConst)arg).setReturnedSignature(types.get(j));
			}
		}
	}

	private static void setConstantTypesPutFieldAndPutStatic(
			ConstantPool constants, Instruction valueref, int index)
	{
		if (valueref.opcode == Const.BIPUSH || valueref.opcode == ByteCodeConstants.ICONST
				|| valueref.opcode == Const.SIPUSH) {
			ConstantFieldref cfr = constants.getConstantFieldref(index);
			ConstantNameAndType cnat =
					constants.getConstantNameAndType(cfr.getNameAndTypeIndex());
			String signature = constants.getConstantUtf8(cnat.getSignatureIndex());
			((IConst)valueref).setReturnedSignature(signature);
		}
	}

	private static void setConstantTypesTernaryOpStore(
			ConstantPool constants, LocalVariables localVariables,
			List<Instruction> list, TernaryOpStore tos)
	{
		if (tos.objectref.opcode == Const.BIPUSH || tos.objectref.opcode == ByteCodeConstants.ICONST
				|| tos.objectref.opcode == Const.SIPUSH) {
			// Recherche de la seconde valeur de l'instruction ternaire
			int index = InstructionUtil.getIndexForOffset(
					list, tos.ternaryOp2ndValueOffset);

			if (index != -1)
			{
				int length = list.size();

				Instruction result;
				while (index < length)
				{
					result = SearchInstructionByOffsetVisitor.visit(
							list.get(index), tos.ternaryOp2ndValueOffset);

					if (result != null)
					{
						String signature =
								result.getReturnedSignature(constants, localVariables);
						((IConst)tos.objectref).setReturnedSignature(signature);
						break;
					}

					index++;
				}
			}
		}
	}

	private static void setConstantTypesArrayStore(
			ConstantPool constants,
			LocalVariables localVariables,
			ArrayStoreInstruction asi)
	{
		if (asi.valueref.opcode == Const.BIPUSH || asi.valueref.opcode == ByteCodeConstants.ICONST
				|| asi.valueref.opcode == Const.SIPUSH) {
			switch (asi.arrayref.opcode)
			{
			case Const.ALOAD:
			{
				ALoad aload = (ALoad)asi.arrayref;
				LocalVariable lv = localVariables.getLocalVariableWithIndexAndOffset(
						aload.index, aload.offset);

				if (lv == null)
				{
					new Throwable("lv is null. index=" + aload.index).printStackTrace();
					return;
				}

				String signature =
						constants.getConstantUtf8(lv.signatureIndex);
				((IConst)asi.valueref).setReturnedSignature(
						SignatureUtil.cutArrayDimensionPrefix(signature));
			}
			break;
			case Const.GETFIELD:
			case Const.GETSTATIC:
			{
				IndexInstruction ii = (IndexInstruction)asi.arrayref;
				ConstantFieldref cfr = constants.getConstantFieldref(ii.index);
				ConstantNameAndType cnat =
						constants.getConstantNameAndType(cfr.getNameAndTypeIndex());
				String signature =
						constants.getConstantUtf8(cnat.getSignatureIndex());
				((IConst)asi.valueref).setReturnedSignature(
						SignatureUtil.cutArrayDimensionPrefix(signature));
			}
			break;
			}
		}
	}

	private static void setConstantTypesIStore(
			ConstantPool constants,
			LocalVariables localVariables,
			Instruction instruction)
	{
		StoreInstruction store = (StoreInstruction)instruction;

		if (store.valueref.opcode == Const.BIPUSH || store.valueref.opcode == ByteCodeConstants.ICONST
				|| store.valueref.opcode == Const.SIPUSH) {
			final LocalVariable lv =
					localVariables.getLocalVariableWithIndexAndOffset(
							store.index, store.offset);
			String signature = constants.getConstantUtf8(lv.signatureIndex);
			((IConst)store.valueref).setReturnedSignature(signature);
		}
	}

	private static void setConstantTypesBinaryOperator(
			ConstantPool constants,
			LocalVariables localVariables,
			Instruction i1, Instruction i2)
	{
		if (i1.opcode == Const.BIPUSH || i1.opcode == ByteCodeConstants.ICONST
				|| i1.opcode == Const.SIPUSH) {
			if (i2.opcode != Const.BIPUSH && i2.opcode != ByteCodeConstants.ICONST
					&& i2.opcode != Const.SIPUSH) {
				String signature = i2.getReturnedSignature(
						constants, localVariables);
				if (signature != null) {
					((IConst)i1).setReturnedSignature(signature);
				}
			}
		} else if (i2.opcode == Const.BIPUSH || i2.opcode == ByteCodeConstants.ICONST
				|| i2.opcode == Const.SIPUSH) {
			String signature = i1.getReturnedSignature(
					constants, localVariables);
			if (signature != null) {
				((IConst)i2).setReturnedSignature(signature);
			}
		}
	}

	private static void setConstantTypesXReturn(
			Instruction instruction, String returnedSignature)
	{
		ReturnInstruction ri = (ReturnInstruction)instruction;

		int opcode = ri.valueref.opcode;

		if (opcode != Const.SIPUSH &&
				opcode != Const.BIPUSH &&
				opcode != ByteCodeConstants.ICONST) {
			return;
		}

		((IConst)ri.valueref).signature = returnedSignature;
	}

	private static String getReturnedSignature(
			ClassFile classFile, Method method)
	{
		AttributeSignature as = method.getAttributeSignature();
		int signatureIndex = as == null ?
				method.getDescriptorIndex() : as.signatureIndex;
		String signature =
				classFile.getConstantPool().getConstantUtf8(signatureIndex);

		return SignatureUtil.getMethodReturnedSignature(signature);
	}

	private static void initialyzeExceptionLoad(
			List<Instruction> listForAnalyze, LocalVariables localVariables)
	{
		int length = listForAnalyze.size();

		/*
		 * Methode d'initialisation des instructions ExceptionLoad non
		 * initialisées. Cela se produit lorsque les méthodes possèdent un bloc
		 * de definition de variables locales.
		 * Les instructions ExceptionLoad appartenant aux blocs 'finally' ne
		 * sont pas initialisée.
		 */
		for (int index=0; index<length; index++)
		{
			Instruction i = listForAnalyze.get(index);

			if (i.opcode == Const.ASTORE)
			{
				AStore as = (AStore)i;

				if (as.valueref.opcode == ByteCodeConstants.EXCEPTIONLOAD)
				{
					ExceptionLoad el = (ExceptionLoad)as.valueref;
					if (el.index == UtilConstants.INVALID_INDEX) {
						el.index = as.index;
					}
				}
			}
		}

		Instruction i;
		/*
		 * Lorsque les exceptions ne sont pas utilisées dans le block 'catch',
		 * aucune variable locale n'est créée. Une pseudo variable locale est
		 * alors créée pour afficher correctement l'instruction
		 * "catch (Exception localException)".
		 * Aucun ajout d'instruction si "ExceptionLoad" correspond à une
		 * instruction "finally".
		 */
		for (int index=0; index<length; index++)
		{
			i = listForAnalyze.get(index);

			if (i.opcode == ByteCodeConstants.EXCEPTIONLOAD)
			{
				ExceptionLoad el = (ExceptionLoad)i;

				if (el.index == UtilConstants.INVALID_INDEX &&
						el.exceptionNameIndex > 0)
				{
					int varIndex = localVariables.size();
					LocalVariable localVariable = new LocalVariable(
							el.offset, 1, UtilConstants.INVALID_INDEX,
							el.exceptionNameIndex, varIndex, true);
					localVariables.add(localVariable);
					el.index = varIndex;
				}
			}
		}
	}

	private static void generateLocalVariableNames(
			ConstantPool constants,
			LocalVariables localVariables,
			DefaultVariableNameGenerator variableNameGenerator)
	{
		final int length = localVariables.size();

		for (int i=localVariables.getIndexOfFirstLocalVariable(); i<length; i++)
		{
			final LocalVariable lv = localVariables.getLocalVariableAt(i);

			if (lv != null && lv.nameIndex <= 0)
			{
				String signature = constants.getConstantUtf8(lv.signatureIndex);
				boolean appearsOnce = signatureAppearsOnceInLocalVariables(
						localVariables, length, lv.signatureIndex);
				String name =
						variableNameGenerator.generateLocalVariableNameFromSignature(
								signature, appearsOnce);
				lv.nameIndex = constants.addConstantUtf8(name);
			}
		}
	}

	private static boolean signatureAppearsOnceInParameters(
			List<String> parameterTypes, int firstIndex,
			int length, String signature)
	{
		int counter = 0;

		for (int i=firstIndex; i<length && counter<2; i++) {
			if (signature.equals(parameterTypes.get(i))) {
				counter++;
			}
		}

		return counter <= 1;
	}

	private static boolean signatureAppearsOnceInLocalVariables(
			LocalVariables localVariables,
			int length, int signatureIndex)
	{
		int counter = 0;

		for (int i=localVariables.getIndexOfFirstLocalVariable();
				i<length && counter<2; i++)
		{
			final LocalVariable lv = localVariables.getLocalVariableAt(i);
			if (lv != null && lv.signatureIndex == signatureIndex) {
				counter++;
			}
		}

		return counter == 1;
	}

	private static boolean reverseAnalyzeIStore(
			LocalVariables localVariables, StoreInstruction si)
	{
		LoadInstruction load = (LoadInstruction)si.valueref;
		LocalVariable lvLoad =
				localVariables.getLocalVariableWithIndexAndOffset(
						load.index, load.offset);

		if (lvLoad == null || lvLoad.signatureIndex != NUMBER_TYPE) {
			return false;
		}

		LocalVariable lvStore =
				localVariables.getLocalVariableWithIndexAndOffset(
						si.index, si.offset);

		if (lvStore == null) {
			return false;
		}

		if (lvStore.signatureIndex == NUMBER_TYPE)
		{
			int old = lvLoad.typesBitField;
			lvLoad.typesBitField &= lvStore.typesBitField;
			return old != lvLoad.typesBitField;
		}
		if (lvStore.signatureIndex >= 0 &&
				lvStore.signatureIndex != lvLoad.signatureIndex)
		{
			lvLoad.signatureIndex = lvStore.signatureIndex;
			return true;
		}

		return false;
	}

	private static boolean reverseAnalyzePutStaticPutField(
			ConstantPool constants, LocalVariables localVariables,
			IndexInstruction ii, LoadInstruction load)
	{
		LocalVariable lvLoad =
				localVariables.getLocalVariableWithIndexAndOffset(
						load.index, load.offset);

		if (lvLoad != null)
		{
			ConstantFieldref cfr = constants.getConstantFieldref(ii.index);
			ConstantNameAndType cnat =
					constants.getConstantNameAndType(cfr.getNameAndTypeIndex());

			if (lvLoad.signatureIndex == NUMBER_TYPE)
			{
				String descriptor = constants.getConstantUtf8(cnat.getSignatureIndex());
				int typesBitField = SignatureUtil.createArgOrReturnBitFields(descriptor);
				int old = lvLoad.typesBitField;
				lvLoad.typesBitField &= typesBitField;
				return old != lvLoad.typesBitField;
			}
			if (lvLoad.signatureIndex == UNDEFINED_TYPE)
			{
				lvLoad.signatureIndex = cnat.getSignatureIndex();
				return true;
			}
		}

		return false;
	}

	private static void addCastInstruction(
			ConstantPool constants, List<Instruction> list,
			LocalVariables localVariables, LocalVariable lv)
	{
		// Add cast instruction before all 'ALoad' instruction for local
		// variable le used type is not 'Object'.
		AddCheckCastVisitor visitor = new AddCheckCastVisitor(
				constants, localVariables, lv);

		final int length = list.size();

		for (int i=0; i<length; i++) {
			visitor.visit(list.get(i));
		}
	}
}
