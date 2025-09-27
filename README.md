# Notification Hub

A backend notification delivery system built with **Spring Boot**, **PostgreSQL** with flyway migrations
It routes notifications to multiple channels while carefully logging each request

---

## Setup
- Java 17
- DockerDesktop
- Git

### Clone
```bash
git clone https://github.com/TaDavid7/notification_hub.git
cd notification-hub
```

### Start PostgreSQL with Docker

```powershell
docker run -d --name notifhub-postgres `
  -e POSTGRES_USER=postgres `
  -e POSTGRES_PASSWORD=pass `
  -e POSTGRES_DB=notification_hub `
  -p 5442:5432 `
  -v notifhub_pgdata:/var/lib/postgresql/data `
  postgres:15
```

###W ebhook URLs

#### Discord
For discord create a server, or one that you have permissions and create a webhook integration. You can do this by going into your channel settings and click on new webhook. You can specify what the name is and channel after clicking the webhook you made. Then copy the webhook URL.

#### Slack
For slack create a workspace, or one that you have permissions and create an app by clicking add apps on the left, and then browse apps. There should be able to click on Build, where you can create an app and then in the incoming WebHooks section create a new WebHook and copy the URL.

#### Configure app settings
Then in the project under src/main/resources/application.yml, you can set the discord and slack webhook URLs with the one you got. Make sure to have " " around them.

Then make sure you are in the same directorty that holds gradlew (notification_hub/notification-hub/gradlew)
```bash
cd notification_hub/notification0hub
.\gradlew clean bootRun
```

And if it works you should see that it connected and you're done!

---
## Future improvements
- JWT security
- Add an event driven notification (automatic)
- Add customization for kind of message for event pipeline

---

## Author
Made by David T.

---

## License 
Licensed under the Apache License 2.0 – see the [LICENSE](LICENSE) file for details.

