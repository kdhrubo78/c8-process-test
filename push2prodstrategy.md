### Deployment flow for a business analyst

- Edit DMN in **Web Modeler**.
- Save a **new process application version** / snapshot so there is a concrete reviewable artifact.
- Run the BPMN harness in **Play** with representative inputs, including happy-path and edge/error scenarios. 
Play scenarios can be stored as **test scenario files** in Web Modeler for reuse.
- If needed, run the same harness on a **dev/test cluster** and inspect execution in **Operate**.
- Have a second person **review and approve** the process application version before prod deployment.

---

### Note

Camunda explicitly positions one-click/manual Web Modeler deployment as suitable for lower-risk cases, 
while business-critical or higher-risk changes should go through CI/CD and stricter governance instead.

Ref - https://docs.camunda.io/docs/components/modeler/web-modeler/process-applications/process-application-pipeline/

### Other References 

- Github Actions - https://github.com/marketplace/actions/camunda-8-action
- Process application - https://docs.camunda.io/docs/8.6/components/modeler/web-modeler/process-applications/
- Play - https://docs.camunda.io/docs/components/modeler/web-modeler/collaboration/play-your-process/
- Test scenario files -https://docs.camunda.io/docs/components/modeler/web-modeler/advanced-modeling/test-scenario-files/
- Run / Publish Process - https://docs.camunda.io/docs/components/modeler/web-modeler/run-or-publish-your-process/