"""Add the nullable session knowledge-base binding without modifying existing rows."""
import pathlib
import subprocess

root = pathlib.Path(__file__).resolve().parents[2]
ssh = ["ssh", "-i", str(pathlib.Path.home() / ".ssh/id_ed25519"),
       "-o", "BatchMode=yes", "-o", "ConnectTimeout=8", "-o", "StrictHostKeyChecking=yes",
       "developer@tianji-dev.example.invalid"]
remote = "docker exec -i mysql sh -c 'export MYSQL_PWD=\"$MYSQL_ROOT_PASSWORD\"; exec mysql -uroot --batch --skip-column-names tj_aigc'"


def execute(sql):
    result = subprocess.run(ssh + [remote], input=sql, text=True, encoding="utf-8",
                            capture_output=True, check=True)
    return result.stdout.strip()


before = execute("SELECT COUNT(*) FROM chat_session;")
schema = (root / "tj-aigc/src/main/resources/db/chat-session-knowledge.sql").read_text(encoding="utf-8")
execute(schema)
execute(schema)  # Check repeated migration without altering existing bindings.
after = execute("SELECT COUNT(*) FROM chat_session;")
assert before == after, "Session row count changed during migration"
column = execute("SELECT CONCAT(DATA_TYPE, ':', IS_NULLABLE) FROM information_schema.COLUMNS "
                 "WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='chat_session' AND COLUMN_NAME='knowledge_base_id';")
assert column == "varchar:YES", "Unexpected knowledge_base_id schema"
print(f"SESSION_KNOWLEDGE_SCHEMA_OK sessions={after} nullable=true repeatable=true")
