package uk.ac.manchester.beehive.tornado.plugins.ui.settings;

import com.intellij.openapi.projectRoots.ProjectJdkTable;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.util.xmlb.Converter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class JdkConverter extends Converter<Sdk> {
    @Override
    public @Nullable Sdk fromString(@NotNull String value) {
        return ProjectJdkTable.getInstance().findJdk(value);
    }

    @Override
    public @Nullable String toString(@NotNull Sdk value) {
        return value.getName();
    }
}
