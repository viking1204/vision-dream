```markdown
# vision-dream Development Patterns

> Auto-generated skill from repository analysis

## Overview
This skill teaches the core development patterns and conventions used in the `vision-dream` Kotlin codebase. While no specific framework is detected, the repository follows clear conventions for file naming, imports, exports, and commit messages. This guide will help you write consistent code, understand testing patterns, and use suggested commands for common workflows.

## Coding Conventions

### File Naming
- Use **PascalCase** for all file names.
  - **Example:** `ImageProcessor.kt`, `DreamVisionService.kt`

### Import Style
- Use **relative imports** for referencing other files or modules.
  - **Example:**
    ```kotlin
    import com.example.visiondream.utils.ImageUtils
    ```

### Export Style
- Use **named exports** for functions, classes, or objects.
  - **Example:**
    ```kotlin
    // File: DreamVisionService.kt
    class DreamVisionService { ... }
    ```

### Commit Messages
- Follow the **conventional commit** format.
- Use the `feat` prefix for new features.
- Keep commit messages concise (average ~30 characters).
  - **Example:**  
    ```
    feat: add image preprocessing step
    ```

## Workflows

### Feature Development
**Trigger:** When adding a new feature or module  
**Command:** `/feature-development`

1. Create a new Kotlin file using PascalCase.
2. Implement the feature using relative imports for dependencies.
3. Export classes or functions using named exports.
4. Write or update corresponding test files (see Testing Patterns).
5. Commit changes using the conventional commit format with the `feat` prefix.

### Importing Modules
**Trigger:** When you need to use code from another file  
**Command:** `/import-module`

1. Use a relative import statement at the top of your Kotlin file.
2. Reference the exported class or function by name.
   - **Example:**
     ```kotlin
     import com.example.visiondream.models.DreamModel
     ```

### Writing Commits
**Trigger:** When committing code changes  
**Command:** `/commit`

1. Write a commit message starting with the `feat` prefix.
2. Keep the message concise and descriptive.
   - **Example:**  
     ```
     feat: improve vision model accuracy
     ```

## Testing Patterns

- Test files follow the `*.test.ts` pattern, indicating TypeScript-based tests.
- The specific testing framework is unknown; however, keep tests in files named after the module being tested with a `.test.ts` suffix.
  - **Example:**  
    ```
    DreamVisionService.test.ts
    ```
- Place test files alongside or in a dedicated test directory as per project structure.

## Commands

| Command                | Purpose                                      |
|------------------------|----------------------------------------------|
| /feature-development   | Start a new feature using repo conventions   |
| /import-module         | Import and use code from another file/module |
| /commit                | Write a conventional commit message          |
```
