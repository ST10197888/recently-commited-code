#1. main

This is the production-ready branch. It contains code that is fully tested and deployed to the live environment. Only merge into this branch when a release is ready. Never commit directly to this branch.

#2. develop

This is the integration branch. All feature branches merge into this branch first. It contains the latest development changes and is used for testing before release. This is where you verify that everything works together.

#3. release

This is the release preparation branch. Create this when you are preparing for a submission or deployment. Use it for final testing and bug fixes before merging into main. Delete it after merging into main and back into develop.

#4. hotfix

This is for emergency fixes to production. Create this only when there is a critical bug in main that needs immediate fixing. Merge back into both main and develop after fixing.

#5. staging

This is the staging environment branch. It mirrors the production environment for final testing before going live. Deploy to Azure Staging from this branch.
