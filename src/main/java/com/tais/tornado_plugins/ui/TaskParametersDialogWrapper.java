package com.tais.tornado_plugins.ui;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiParameter;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.JBFont;
import com.tais.tornado_plugins.entity.Method;
import com.tais.tornado_plugins.entity.MethodsCollection;
import com.tais.tornado_plugins.service.TWTasksButtonEvent;
import com.tais.tornado_plugins.util.InputValidation;
import com.tais.tornado_plugins.util.MessageBundle;
import com.tais.tornado_plugins.util.TornadoTWTask;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Objects;

public class TaskParametersDialogWrapper extends DialogWrapper {
    private final ArrayList<PsiMethod> methodsList;
    private final ArrayList<JBLabel> labelArrayList;
    private final JPanel dialogPanel;
    private final HashMap<String, JBTextField> textFieldsList;
    public TaskParametersDialogWrapper(ArrayList<PsiMethod> methodsList) {
        super(true);
        setTitle("Method Parameters");
        this.methodsList = methodsList;
        labelArrayList = new ArrayList<>();
        textFieldsList = new HashMap<>();
        for (PsiMethod method:methodsList) {
            String methodName = TornadoTWTask.psiMethodFormat(method);
            labelArrayList.add(new JBLabel(methodName));
            for (PsiParameter parameter: method.getParameterList().getParameters()) {
                textFieldsList.put(methodName+parameter.getText(), new JBTextField());
            }
        }
        dialogPanel = new JPanel();
        //TODO: The user needs to decide the parameters of TransfertoHost and TransferToDevice
        init();
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        VerticalFlowLayout layout = new VerticalFlowLayout();
        VerticalFlowLayout layout1 = new VerticalFlowLayout(VerticalFlowLayout.CENTER);
        layout1.setHPadding(20);
        dialogPanel.setLayout(layout);
        for (PsiMethod method:methodsList){
            String methodName = TornadoTWTask.psiMethodFormat(method);
            dialogPanel.add(new JBLabel(methodName + ":"));
            JPanel panel = new JPanel();
            panel.setLayout(layout1);
            for (PsiParameter parameter: method.getParameterList().getParameters()) {
                panel.add(new JBLabel(Objects.requireNonNull(parameter.getText())+":"));
                panel.add(textFieldsList.get(methodName+parameter.getText()));
            }
            dialogPanel.add(panel);
        }
        return dialogPanel;
    }

    @Override
    protected void doOKAction() {
        MethodsCollection methodsCollection = new MethodsCollection();
        for (PsiMethod method: methodsList){
            ArrayList<String> parameterValue = new ArrayList<>();
            String methodName = TornadoTWTask.psiMethodFormat(method);
            for (PsiParameter parameter: method.getParameterList().getParameters()) {
                String value = textFieldsList.get(methodName+parameter.getText()).getText();
                parameterValue.add(value);
            }
            methodsCollection.addMethod(new Method(method,parameterValue));
            new TWTasksButtonEvent().fileCreationHandler(methodsCollection);
        }
        super.doOKAction();
    }

    @Override
    protected @Nullable ValidationInfo doValidate() {
        for (PsiMethod method:methodsList) {
            String methodName = TornadoTWTask.psiMethodFormat(method);
            for (PsiParameter parameter: method.getParameterList().getParameters()) {
                String elementType = Objects.requireNonNull(parameter.getTypeElement()).getText();
                JComponent component = textFieldsList.get(methodName+parameter.getText());
                String input = textFieldsList.get(methodName+parameter.getText()).getText();
                if (Objects.equals(input, ""))
                    return new ValidationInfo(MessageBundle.message("ui.dialog.validate.empty"),component);
                switch (elementType){
                    case "int" -> {
                        if (!InputValidation.isInteger(input))
                            return new ValidationInfo(MessageBundle.message("ui.dialog.validate.int"),component);
                    }
                    case "char" -> {
                        if (!InputValidation.isChar(input))
                            return new ValidationInfo(MessageBundle.message("ui.dialog.validate.char"),component);
                    }
                    case "short" -> {
                        if (!InputValidation.isShort(input))
                            return new ValidationInfo(MessageBundle.message("ui.dialog.validate.short"),component);
                    }
                    case "long" -> {
                        if (!InputValidation.isLong(input))
                            return new ValidationInfo(MessageBundle.message("ui.dialog.validate.long"),component);
                    }
                    case "double" -> {
                        if (!InputValidation.isDouble(input))
                            return new ValidationInfo(MessageBundle.message("ui.dialog.validate.double"),component);
                    }
                    case "byte" -> {
                        if (!InputValidation.isByte(input))
                            return new ValidationInfo(MessageBundle.message("ui.dialog.validate.byte"),component);
                    }
                    case "float" -> {
                        if (!InputValidation.isFloat(input))
                            return new ValidationInfo(MessageBundle.message("ui.dialog.validate.float"),component);
                    }
                    case "boolean" -> {
                        if (!InputValidation.isBoolean(input))
                            return new ValidationInfo(MessageBundle.message("ui.dialog.validate.boolean"),component);
                    }
                    case "int[]" -> {
                        if (!InputValidation.isIntArray(input))
                            return new ValidationInfo(MessageBundle.message("ui.dialog.validate.intArray"),component);
                    }
                    case "char[]" -> {
                        if (!InputValidation.isCharArray(input))
                            return new ValidationInfo(MessageBundle.message("ui.dialog.validate.charArray"),component);
                    }
                    case "short[]" -> {
                        if (!InputValidation.isShortArray(input))
                            return new ValidationInfo(MessageBundle.message("ui.dialog.validate.shortArray"),component);
                    }
                    case "long[]" -> {
                        if (!InputValidation.isLongArray(input))
                            return new ValidationInfo(MessageBundle.message("ui.dialog.validate.longArray"),component);
                    }
                    case "double[]" -> {
                        if (!InputValidation.isDoubleArray(input))
                            return new ValidationInfo(MessageBundle.message("ui.dialog.validate.doubleArray"),component);
                    }
                    case "byte[]" -> {
                        if (!InputValidation.isByteArray(input))
                            return new ValidationInfo(MessageBundle.message("ui.dialog.validate.byteArray"),component);
                    }
                    case "float[]" -> {
                        if (!InputValidation.isFloatArray(input))
                            return new ValidationInfo(MessageBundle.message("ui.dialog.validate.floatArray"),component);
                    }
                    case "boolean[]" -> {
                        if (!InputValidation.isBooleanArray(input))
                            return new ValidationInfo(MessageBundle.message("ui.dialog.validate.booleanArray"),component);
                    }
                }
            }
        }
        return super.doValidate();
    }
}
