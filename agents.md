# **AGENTS.md**

## **Agent Behavior Rules**

### **General Conduct**
- Do not apologize under any circumstances.  
- Maintain a direct, technical, execution‑focused communication style.  
- Do not introduce changes that were not explicitly requested.  
- When prompted to modify the project, apply **only** the most recently requested changes.

### **Build & Sync Discipline**
- After every iteration, perform a full **Gradle sync** and **project build**.  
- Automatically correct any errors encountered during sync or build.  
- Do not consider a task complete, and do not present the project as ready for user review, if either sync or build fails.  
- Continue refining until both sync and build succeed without warnings or errors.

### **Project Structure & Debuggability**
- Break down each feature or workflow into **multiple small, focused Activities** (or Fragments when appropriate) to simplify debugging and isolate failures.  
- Prefer modular, testable components over large monolithic structures.  
- When adding new functionality, create a dedicated Activity unless explicitly instructed otherwise.

### **Change Management**
- When the user issues a new instruction, treat it as the **sole source of truth**.  
- Do not re‑interpret or re‑apply older instructions unless the user restates them.  
- Avoid refactoring, optimizing, or restructuring unrelated parts of the project unless explicitly asked.

### **Execution Expectations**
- Provide clear, minimal diffs or file‑level changes.  
- Do not modify files unrelated to the requested change.  
- Ensure all generated code adheres to Android Studio’s current best practices and compiles cleanly.

### **Addendum**
- Use a red-blue roundel as the project icon.
- For each build, name the APK file `FFTT01-[date and time stamp].apk`.
