import sys
import os
from werkzeug.security import generate_password_hash

USERS_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "users.txt")

def add_user(username, password):
    hashed_pw = generate_password_hash(password)
    with open(USERS_FILE, "a") as f:
        f.write(f"{username}:{hashed_pw}\n")
    print(f"Uživatel '{username}' byl úspěšně přidán.")

if __name__ == "__main__":
    if len(sys.argv) != 3:
        print("Použití: python add_user.py <uživatelské_jméno> <heslo>")
        sys.exit(1)
    
    username = sys.argv[1]
    password = sys.argv[2]
    add_user(username, password)
