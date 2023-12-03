package uk.ac.manchester.beehive.tornado.plugins.listener;

import com.intellij.psi.PsiTreeChangeEvent;
import com.intellij.psi.PsiTreeChangeListener;
import uk.ac.manchester.beehive.tornado.plugins.entity.ProblemMethods;
import org.jetbrains.annotations.NotNull;

public class PsiChangeListener implements PsiTreeChangeListener {
    @Override
    public void beforeChildAddition(@NotNull PsiTreeChangeEvent event) {

    }

    @Override
    public void beforeChildRemoval(@NotNull PsiTreeChangeEvent event) {

    }

    @Override
    public void beforeChildReplacement(@NotNull PsiTreeChangeEvent event) {

    }

    @Override
    public void beforeChildMovement(@NotNull PsiTreeChangeEvent event) {

    }

    @Override
    public void beforeChildrenChange(@NotNull PsiTreeChangeEvent event) {

    }

    @Override
    public void beforePropertyChange(@NotNull PsiTreeChangeEvent event) {

    }

    @Override
    public void childAdded(@NotNull PsiTreeChangeEvent event) {
        ProblemMethods.getInstance().clear();
    }

    @Override
    public void childRemoved(@NotNull PsiTreeChangeEvent event) {
        ProblemMethods.getInstance().clear();
    }

    @Override
    public void childReplaced(@NotNull PsiTreeChangeEvent event) {
        ProblemMethods.getInstance().clear();
    }

    @Override
    public void childrenChanged(@NotNull PsiTreeChangeEvent event) {
        ProblemMethods.getInstance().clear();
    }

    @Override
    public void childMoved(@NotNull PsiTreeChangeEvent event) {
        ProblemMethods.getInstance().clear();
    }

    @Override
    public void propertyChanged(@NotNull PsiTreeChangeEvent event) {
        ProblemMethods.getInstance().clear();
    }
}
